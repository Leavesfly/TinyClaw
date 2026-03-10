package io.leavesfly.tinyclaw.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.config.ToolsConfig;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 技能搜索器 - 从可信技能市场和 GitHub 搜索可安装的技能
 * 
 * 采用分层搜索策略，优先从可信技能市场（Skill Registry）搜索，
 * 仅在用户显式启用时才降级到 GitHub 全网搜索。
 * 
 * 搜索优先级：
 * 1. 可信技能市场：从配置的 registry 仓库中加载 registry.json 索引并匹配
 * 2. 可信仓库目录扫描：直接扫描可信仓库中的子目录查找 SKILL.md
 * 3. GitHub 全网搜索（需 allowGlobalSearch=true）：按 topic 和文件名搜索
 * 
 * 安全策略：
 * - 默认只从可信市场源搜索，避免安装未经审核的代码
 * - GitHub 全网搜索默认关闭，需要用户在配置中显式启用
 * - 全网搜索结果会带有安全警告标记
 * 
 * 内置可信市场源：
 * - leavesfly/tinyclaw-skills：官方维护的技能集合
 * - tinyclaw-community/awesome-skills：社区精选技能
 * - jasonkneen/claude-code-skills：Claude Code 社区技能集合
 * - anthropics/claude-code-skills：Anthropic 官方技能集合
 */
public class SkillsSearcher {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("skills");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITHUB_SEARCH_REPOS = GITHUB_API_BASE + "/search/repositories";
    private static final String GITHUB_SEARCH_CODE = GITHUB_API_BASE + "/search/code";
    private static final String GITHUB_CONTENTS_API = GITHUB_API_BASE + "/repos/%s/contents/%s";
    private static final String GITHUB_REPO_CONTENTS_API = GITHUB_API_BASE + "/repos/%s/contents";

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final String USER_AGENT = "tinyclaw/1.0";

    private final OkHttpClient httpClient;
    private final String githubToken;
    private final boolean allowGlobalSearch;
    private final List<SkillRegistry> registries;
    private volatile boolean lastSearchRateLimited = false;

    /**
     * 创建技能搜索器（使用默认配置）
     * 
     * 默认只从内置可信市场源搜索，不启用 GitHub 全网搜索。
     */
    public SkillsSearcher() {
        this(null, false, null);
    }

    /**
     * 创建技能搜索器
     * 
     * @param githubToken GitHub Personal Access Token（可选，提高速率限制）
     * @param allowGlobalSearch 是否允许 GitHub 全网搜索
     * @param customRegistries 自定义技能市场源（为 null 时使用默认源）
     */
    public SkillsSearcher(String githubToken, boolean allowGlobalSearch,
                          List<SkillRegistry> customRegistries) {
        this.githubToken = githubToken;
        this.allowGlobalSearch = allowGlobalSearch;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        // 合并默认市场源和自定义市场源
        this.registries = new ArrayList<>(SkillRegistry.getDefaultRegistries());
        if (customRegistries != null) {
            for (SkillRegistry custom : customRegistries) {
                if (custom.isEnabled() && registries.stream()
                        .noneMatch(r -> r.getRepo().equals(custom.getRepo()))) {
                    registries.add(custom);
                }
            }
        }
    }

    /**
     * 从 ToolsConfig 创建技能搜索器
     * 
     * @param skillsConfig 技能工具配置
     * @return 配置好的技能搜索器
     */
    public static SkillsSearcher fromConfig(ToolsConfig.SkillsToolConfig skillsConfig) {
        if (skillsConfig == null) {
            return new SkillsSearcher();
        }

        List<SkillRegistry> customRegistries = new ArrayList<>();
        if (skillsConfig.getRegistries() != null) {
            for (ToolsConfig.RegistryConfig registryConfig : skillsConfig.getRegistries()) {
                if (registryConfig.isEnabled()) {
                    customRegistries.add(new SkillRegistry(
                            registryConfig.getName(),
                            registryConfig.getRepo(),
                            registryConfig.getDescription()
                    ));
                }
            }
        }

        String token = skillsConfig.getGithubToken();
        if (token != null && token.isEmpty()) {
            token = null;
        }

        return new SkillsSearcher(token, skillsConfig.isAllowGlobalSearch(), customRegistries);
    }

    /**
     * 搜索技能
     * 
     * 按优先级执行分层搜索：
     * 1. 从可信技能市场的 registry.json 索引中搜索
     * 2. 扫描可信仓库的目录结构查找 SKILL.md
     * 3. 如果启用了全网搜索且结果不足，降级到 GitHub 全网搜索
     * 
     * @param query 搜索关键词（描述需要的技能功能）
     * @param maxResults 最大返回结果数
     * @return 搜索结果列表
     */
    public List<SkillSearchResult> search(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        int limit = maxResults > 0 && maxResults <= 10 ? maxResults : DEFAULT_MAX_RESULTS;
        List<SkillSearchResult> results = new ArrayList<>();
        boolean rateLimited = false;

        // 阶段 1：从可信技能市场搜索
        for (SkillRegistry registry : registries) {
            if (!registry.isEnabled() || results.size() >= limit) {
                break;
            }

            // 如果已经检测到限流，跳过后续 registry 源，避免浪费请求
            if (rateLimited) {
                logger.info("Skipping registry due to rate limit", Map.of(
                        "registry", registry.getName()
                ));
                continue;
            }

            try {
                List<SkillSearchResult> registryResults = searchFromRegistry(registry, query, limit - results.size());
                for (SkillSearchResult registryResult : registryResults) {
                    if (results.stream().noneMatch(r -> r.getFullName().equals(registryResult.getFullName())
                            && equalSubdir(r.getSkillSubdir(), registryResult.getSkillSubdir()))) {
                        results.add(registryResult);
                    }
                }
                logger.info("Registry search completed", Map.of(
                        "registry", registry.getName(),
                        "query", query,
                        "results", registryResults.size()
                ));
            } catch (GitHubRateLimitException e) {
                rateLimited = true;
                logger.warn("GitHub API rate limit hit, skipping remaining registries", Map.of(
                        "registry", registry.getName(),
                        "error", e.getMessage()
                ));
            } catch (Exception e) {
                logger.warn("Registry search failed", Map.of(
                        "registry", registry.getName(),
                        "error", e.getMessage()
                ));
            }
        }

        // 阶段 2：如果启用了全网搜索且结果不足且未限流，降级到 GitHub 全网搜索
        if (allowGlobalSearch && results.size() < limit && !rateLimited) {
            try {
                List<SkillSearchResult> globalResults = searchGitHubGlobal(query, limit - results.size());
                for (SkillSearchResult globalResult : globalResults) {
                    if (results.stream().noneMatch(r -> r.getFullName().equals(globalResult.getFullName()))) {
                        globalResult.setTrusted(false);
                        results.add(globalResult);
                    }
                }
                logger.info("Global GitHub search completed", Map.of(
                        "query", query,
                        "additional_results", globalResults.size()
                ));
            } catch (GitHubRateLimitException e) {
                rateLimited = true;
                logger.warn("GitHub API rate limit hit during global search", Map.of("error", e.getMessage()));
            } catch (Exception e) {
                logger.warn("Global GitHub search failed", Map.of("error", e.getMessage()));
            }
        }

        // 限制最终结果数量
        if (results.size() > limit) {
            results = new ArrayList<>(results.subList(0, limit));
        }

        // 如果因限流导致无结果，设置标记以便 formatResults 给出准确提示
        if (results.isEmpty() && rateLimited) {
            this.lastSearchRateLimited = true;
        } else {
            this.lastSearchRateLimited = false;
        }

        return results;
    }

    /**
     * 从单个可信技能市场搜索
     * 
     * 搜索策略：
     * 1. 尝试加载 registry.json 索引文件并匹配关键词
     * 2. 如果没有 registry.json，扫描仓库根目录查找包含 SKILL.md 的子目录
     */
    private List<SkillSearchResult> searchFromRegistry(SkillRegistry registry, String query, int limit) throws Exception {
        List<SkillSearchResult> results = new ArrayList<>();

        // 尝试加载 registry.json
        List<SkillSearchResult> indexResults = searchFromRegistryIndex(registry, query, limit);
        if (!indexResults.isEmpty()) {
            results.addAll(indexResults);
            return results;
        }

        // 没有 registry.json，扫描仓库目录
        List<SkillSearchResult> dirResults = searchFromRepoDirectory(registry, query, limit);
        results.addAll(dirResults);

        return results;
    }

    /**
     * GitHub API 速率限制异常
     */
    private static class GitHubRateLimitException extends Exception {
        public GitHubRateLimitException(String message) {
            super(message);
        }
    }

    /**
     * 从 registry.json 索引文件搜索
     * 
     * 加载可信仓库根目录下的 registry.json 文件，解析其中的技能列表，
     * 并按关键词匹配返回结果。
     */
    private List<SkillSearchResult> searchFromRegistryIndex(SkillRegistry registry, String query, int limit) throws GitHubRateLimitException {
        List<SkillSearchResult> results = new ArrayList<>();

        try {
            String url = String.format(GITHUB_CONTENTS_API, registry.getRepo(), "registry.json");
            String responseBody = executeGitHubRequest(url);
            JsonNode fileNode = objectMapper.readTree(responseBody);

            // GitHub Contents API 返回 base64 编码的文件内容
            String content = fileNode.path("content").asText("");
            if (content.isEmpty()) {
                return results;
            }

            // 解码 base64 内容（GitHub 返回的 content 可能包含换行符）
            String cleanContent = content.replaceAll("\\s", "");
            String decodedContent = new String(Base64.getDecoder().decode(cleanContent), StandardCharsets.UTF_8);

            SkillRegistry.RegistryIndex index = objectMapper.readValue(decodedContent, SkillRegistry.RegistryIndex.class);

            if (index.getSkills() != null) {
                for (SkillRegistry.SkillEntry entry : index.getSkills()) {
                    if (results.size() >= limit) {
                        break;
                    }
                    if (entry.matches(query)) {
                        SkillSearchResult result = new SkillSearchResult();
                        result.setFullName(entry.getRepo() != null ? entry.getRepo() : registry.getRepo());
                        result.setDescription(entry.getDescription());
                        result.setSkillSubdir(entry.getSubdir());
                        result.setHasSkillFile(true);
                        result.setTrusted(true);
                        result.setRegistrySource(registry.getName());
                        result.setUrl("https://github.com/" + result.getFullName());
                        if (entry.getName() != null) {
                            result.setSkillName(entry.getName());
                        }
                        results.add(result);
                    }
                }
            }
        } catch (GitHubRateLimitException e) {
            throw e;
        } catch (Exception e) {
            // registry.json 不存在或解析失败，忽略
            logger.debug("No registry.json found for " + registry.getRepo() + ": " + e.getMessage());
        }

        return results;
    }

    /**
     * 扫描可信仓库目录查找技能
     * 
     * 当仓库没有 registry.json 时，直接通过 GitHub Contents API
     * 列出仓库根目录下的子目录，检查每个子目录是否包含 SKILL.md。
     * 匹配策略：
     * 1. 先按目录名匹配关键词（快速匹配）
     * 2. 目录名不匹配时，尝试读取 SKILL.md 内容做关键词匹配（深度匹配）
     */
    private List<SkillSearchResult> searchFromRepoDirectory(SkillRegistry registry, String query, int limit) throws GitHubRateLimitException {
        List<SkillSearchResult> results = new ArrayList<>();

        try {
            String url = String.format(GITHUB_REPO_CONTENTS_API, registry.getRepo());
            String responseBody = executeGitHubRequest(url);
            JsonNode items = objectMapper.readTree(responseBody);

            if (!items.isArray()) {
                return results;
            }

            String lowerQuery = query.toLowerCase();
            String[] keywords = lowerQuery.split("\\s+");

            for (JsonNode item : items) {
                if (results.size() >= limit) {
                    break;
                }

                String type = item.path("type").asText("");
                String dirName = item.path("name").asText("");

                // 只处理目录，跳过隐藏目录和特殊文件
                if (!"dir".equals(type) || dirName.startsWith(".") || dirName.startsWith("_")) {
                    continue;
                }

                // 阶段1：检查目录名是否匹配任一关键词（快速匹配）
                boolean nameMatches = false;
                for (String keyword : keywords) {
                    if (dirName.toLowerCase().contains(keyword)) {
                        nameMatches = true;
                        break;
                    }
                }

                if (nameMatches) {
                    // 目录名匹配，验证是否包含 SKILL.md
                    if (verifySkillFile(registry.getRepo(), dirName)) {
                        SkillSearchResult result = buildDirectoryResult(registry, dirName, null);
                        results.add(result);
                    }
                } else {
                    // 阶段2：目录名不匹配，尝试读取 SKILL.md 内容做深度匹配
                    String skillContent = fetchSkillFileContent(registry.getRepo(), dirName);
                    if (skillContent != null && matchesKeywords(skillContent, keywords)) {
                        SkillSearchResult result = buildDirectoryResult(registry, dirName, skillContent);
                        results.add(result);
                    }
                }
            }
        } catch (GitHubRateLimitException e) {
            throw e;
        } catch (Exception e) {
            logger.debug("Directory scan failed for " + registry.getRepo() + ": " + e.getMessage());
        }

        return results;
    }

    /**
     * 构建目录扫描的搜索结果
     */
    private SkillSearchResult buildDirectoryResult(SkillRegistry registry, String dirName, String skillContent) {
        SkillSearchResult result = new SkillSearchResult();
        result.setFullName(registry.getRepo());
        result.setSkillSubdir(dirName);
        result.setSkillName(dirName);
        result.setHasSkillFile(true);
        result.setTrusted(true);
        result.setRegistrySource(registry.getName());
        result.setUrl("https://github.com/" + registry.getRepo() + "/tree/main/" + dirName);

        // 尝试从 SKILL.md 内容中提取描述（取第一行非空非标题行）
        if (skillContent != null) {
            String description = extractDescriptionFromSkillContent(skillContent);
            result.setDescription(description != null ? description : dirName);
        } else {
            result.setDescription(registry.getDescription() + " - " + dirName);
        }

        return result;
    }

    /**
     * 获取 SKILL.md 文件内容
     * 
     * @param repoFullName 仓库全名
     * @param subdir 子目录
     * @return SKILL.md 的文本内容，不存在则返回 null
     */
    private String fetchSkillFileContent(String repoFullName, String subdir) {
        String path = (subdir != null && !subdir.isEmpty()) ? subdir + "/SKILL.md" : "SKILL.md";
        String url = String.format(GITHUB_CONTENTS_API, repoFullName, path);

        try {
            String responseBody = executeGitHubRequest(url);
            JsonNode fileNode = objectMapper.readTree(responseBody);
            String content = fileNode.path("content").asText("");
            if (content.isEmpty()) {
                return null;
            }
            String cleanContent = content.replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(cleanContent), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查文本内容是否匹配任一关键词
     */
    private boolean matchesKeywords(String content, String[] keywords) {
        String lowerContent = content.toLowerCase();
        for (String keyword : keywords) {
            if (lowerContent.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 SKILL.md 内容中提取描述信息
     * 
     * 取第一行非空、非 Markdown 标题的文本作为描述。
     */
    private String extractDescriptionFromSkillContent(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---")) {
                return trimmed.length() > 120 ? trimmed.substring(0, 120) + "..." : trimmed;
            }
        }
        return null;
    }

    /**
     * GitHub 全网搜索（降级策略）
     * 
     * 当可信市场中找不到匹配技能时，如果用户启用了 allowGlobalSearch，
     * 则通过 GitHub Search API 进行全网搜索。结果会被标记为"未经审核"。
     */
    private List<SkillSearchResult> searchGitHubGlobal(String query, int limit) throws Exception {
        List<SkillSearchResult> results = new ArrayList<>();

        // 搜索带有 tinyclaw-skill topic 的仓库
        try {
            String searchQuery = query + " topic:tinyclaw-skill";
            String url = GITHUB_SEARCH_REPOS + "?q=" + encodeQuery(searchQuery)
                    + "&sort=stars&order=desc&per_page=" + limit;
            results.addAll(executeRepoSearch(url));
        } catch (Exception e) {
            logger.warn("Topic search failed", Map.of("error", e.getMessage()));
        }

        // 搜索包含 SKILL.md 文件的仓库
        if (results.size() < limit) {
            try {
                String searchQuery = query + " filename:SKILL.md";
                String url = GITHUB_SEARCH_CODE + "?q=" + encodeQuery(searchQuery)
                        + "&per_page=" + (limit - results.size());
                List<SkillSearchResult> codeResults = executeCodeSearch(url);
                for (SkillSearchResult codeResult : codeResults) {
                    if (results.stream().noneMatch(r -> r.getFullName().equals(codeResult.getFullName()))) {
                        results.add(codeResult);
                    }
                }
            } catch (Exception e) {
                logger.warn("Code search failed", Map.of("error", e.getMessage()));
            }
        }

        return results;
    }

    /**
     * 执行仓库搜索 API 调用并解析结果
     */
    private List<SkillSearchResult> executeRepoSearch(String url) throws Exception {
        List<SkillSearchResult> results = new ArrayList<>();

        String responseBody = executeGitHubRequest(url);
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode items = root.path("items");

        if (items.isArray()) {
            for (JsonNode item : items) {
                SkillSearchResult result = parseRepoItem(item);
                if (result != null) {
                    results.add(result);
                }
            }
        }

        return results;
    }

    /**
     * 执行代码搜索 API 调用并解析结果
     */
    private List<SkillSearchResult> executeCodeSearch(String url) throws Exception {
        List<SkillSearchResult> results = new ArrayList<>();

        String responseBody = executeGitHubRequest(url);
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode items = root.path("items");

        if (items.isArray()) {
            for (JsonNode item : items) {
                JsonNode repo = item.path("repository");
                if (!repo.isMissingNode()) {
                    SkillSearchResult result = parseRepoItem(repo);
                    if (result != null) {
                        result.setHasSkillFile(true);
                        String filePath = item.path("path").asText("");
                        if (!filePath.isEmpty() && !filePath.equals("SKILL.md")) {
                            String parentDir = filePath.substring(0, filePath.lastIndexOf('/'));
                            result.setSkillSubdir(parentDir);
                        }
                        results.add(result);
                    }
                }
            }
        }

        return results;
    }

    /**
     * 验证仓库是否包含 SKILL.md 文件
     * 
     * @param fullName 仓库全名（owner/repo）
     * @param subdir 子目录路径（可选）
     * @return 是否包含 SKILL.md
     */
    public boolean verifySkillFile(String fullName, String subdir) {
        String path = (subdir != null && !subdir.isEmpty()) ? subdir + "/SKILL.md" : "SKILL.md";
        String url = String.format(GITHUB_CONTENTS_API, fullName, path);

        try {
            executeGitHubRequest(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析仓库 JSON 节点为搜索结果
     */
    private SkillSearchResult parseRepoItem(JsonNode item) {
        String fullName = item.path("full_name").asText("");
        if (fullName.isEmpty()) {
            return null;
        }

        SkillSearchResult result = new SkillSearchResult();
        result.setFullName(fullName);
        result.setDescription(item.path("description").asText(""));
        result.setStars(item.path("stargazers_count").asInt(0));
        result.setUrl(item.path("html_url").asText(""));
        result.setLanguage(item.path("language").asText(""));
        result.setUpdatedAt(item.path("updated_at").asText(""));

        JsonNode topics = item.path("topics");
        if (topics.isArray()) {
            List<String> topicList = new ArrayList<>();
            for (JsonNode topic : topics) {
                topicList.add(topic.asText());
            }
            result.setTopics(topicList);
            result.setHasSkillFile(topicList.contains("tinyclaw-skill"));
        }

        return result;
    }

    /**
     * 执行 GitHub API 请求
     */
    private String executeGitHubRequest(String url) throws Exception {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", USER_AGENT);

        if (githubToken != null && !githubToken.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + githubToken);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (response.code() == 403) {
                String remaining = response.header("X-RateLimit-Remaining", "");
                if ("0".equals(remaining) || (response.body() != null
                        && response.peekBody(512).string().contains("rate limit"))) {
                    throw new GitHubRateLimitException(
                            "GitHub API 速率限制已达上限（未认证请求限制 60 次/小时）。"
                            + "请在 ~/.tinyclaw/config.json 的 tools.skills.githubToken 中配置 GitHub Token 以提高至 5000 次/小时，"
                            + "或等待限额重置后重试。");
                }
                throw new Exception("GitHub API 请求被拒绝: HTTP 403");
            }
            if (response.code() == 404) {
                throw new Exception("GitHub 资源不存在: " + url);
            }
            if (!response.isSuccessful()) {
                throw new Exception("GitHub API 请求失败: HTTP " + response.code());
            }

            return response.body() != null ? response.body().string() : "{}";
        }
    }

    private String encodeQuery(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    private boolean equalSubdir(String subdir1, String subdir2) {
        if (subdir1 == null && subdir2 == null) return true;
        if (subdir1 == null || subdir2 == null) return false;
        return subdir1.equals(subdir2);
    }

    /**
     * 获取当前配置的可信技能市场源列表
     * 
     * @return 市场源列表
     */
    public List<SkillRegistry> getRegistries() {
        return registries;
    }

    /**
     * 是否启用了 GitHub 全网搜索
     * 
     * @return 是否允许全网搜索
     */
    public boolean isAllowGlobalSearch() {
        return allowGlobalSearch;
    }

    /**
     * 格式化搜索结果为人类可读的字符串
     * 
     * @param results 搜索结果列表
     * @param query 原始搜索关键词
     * @return 格式化的结果字符串
     */
    public String formatResults(List<SkillSearchResult> results, String query) {
        if (results.isEmpty()) {
            StringBuilder emptyMsg = new StringBuilder();
            emptyMsg.append("未找到与 '").append(query).append("' 相关的技能。\n\n");

            if (this.lastSearchRateLimited) {
                emptyMsg.append("⚠️ 原因：GitHub API 速率限制已耗尽\n");
                emptyMsg.append("未认证请求限制为 60 次/小时，当前配额已用完。\n\n");
                emptyMsg.append("解决方案：\n");
                emptyMsg.append("- 配置 GitHub Token 以提高至 5000 次/小时（在 ~/.tinyclaw/config.json 的 tools.skills.githubToken 中设置）\n");
                emptyMsg.append("- 等待约 1 小时后限额自动重置\n");
                emptyMsg.append("- 手动安装已知仓库：`skills(action='install', repo='owner/repo')`\n");
                emptyMsg.append("- 自己创建一个技能：`skills(action='create', ...)`");
            } else {
                emptyMsg.append("可能的原因：\n");
                emptyMsg.append("- 关键词与技能目录名或 SKILL.md 内容不匹配，请尝试换用英文或更通用的关键词\n");
                emptyMsg.append("- 可信市场源仓库可能暂时不可访问\n\n");
                emptyMsg.append("建议：\n");
                emptyMsg.append("- 尝试使用不同的关键词搜索，如：`skills(action='search', query='code review')`\n");
                emptyMsg.append("- 配置 GitHub Token 以提高 API 速率限制（在 ~/.tinyclaw/config.json 的 tools.skills.githubToken 中设置）\n");
                emptyMsg.append("- 启用全网搜索以扩大搜索范围（在 ~/.tinyclaw/config.json 的 tools.skills.allowGlobalSearch 设为 true）\n");
                emptyMsg.append("- 手动安装已知仓库：`skills(action='install', repo='owner/repo')`\n");
                emptyMsg.append("- 自己创建一个技能：`skills(action='create', ...)`");
            }
            return emptyMsg.toString();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 个与 '").append(query).append("' 相关的技能：\n\n");

        for (int i = 0; i < results.size(); i++) {
            SkillSearchResult result = results.get(i);
            sb.append(i + 1).append(". ");

            // 显示技能名称或仓库名
            if (result.getSkillName() != null && !result.getSkillName().isEmpty()) {
                sb.append("**").append(result.getSkillName()).append("**");
                sb.append(" (").append(result.getFullName()).append(")");
            } else {
                sb.append("**").append(result.getFullName()).append("**");
            }

            if (result.getStars() > 0) {
                sb.append(" ⭐ ").append(result.getStars());
            }
            sb.append("\n");

            if (result.getDescription() != null && !result.getDescription().isEmpty()) {
                sb.append("   ").append(result.getDescription()).append("\n");
            }

            // 来源标记
            if (result.isTrusted()) {
                sb.append("   🔒 来源: ").append(result.getRegistrySource() != null
                        ? result.getRegistrySource() : "可信市场").append("\n");
            } else {
                sb.append("   ⚠️ 来源: GitHub 全网搜索（未经审核，请自行验证安全性）\n");
            }

            if (result.isHasSkillFile()) {
                sb.append("   ✅ 包含 SKILL.md\n");
            }

            String installCmd = result.getInstallSpecifier();
            sb.append("   安装: `skills(action='install', repo='").append(installCmd).append("')`\n");
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("直接安装: `skills(action='install', repo='owner/repo')`\n");
        sb.append("一键搜索安装: `skills(action='search_install', query='").append(query).append("')`");

        return sb.toString();
    }

    /**
     * 技能搜索结果
     */
    public static class SkillSearchResult {
        private String fullName;
        private String skillName;
        private String description;
        private int stars;
        private String url;
        private String language;
        private String updatedAt;
        private List<String> topics;
        private boolean hasSkillFile;
        private String skillSubdir;
        private boolean trusted = true;
        private String registrySource;

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }

        public String getSkillName() { return skillName; }
        public void setSkillName(String skillName) { this.skillName = skillName; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public int getStars() { return stars; }
        public void setStars(int stars) { this.stars = stars; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }

        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

        public List<String> getTopics() { return topics; }
        public void setTopics(List<String> topics) { this.topics = topics; }

        public boolean isHasSkillFile() { return hasSkillFile; }
        public void setHasSkillFile(boolean hasSkillFile) { this.hasSkillFile = hasSkillFile; }

        public String getSkillSubdir() { return skillSubdir; }
        public void setSkillSubdir(String skillSubdir) { this.skillSubdir = skillSubdir; }

        public boolean isTrusted() { return trusted; }
        public void setTrusted(boolean trusted) { this.trusted = trusted; }

        public String getRegistrySource() { return registrySource; }
        public void setRegistrySource(String registrySource) { this.registrySource = registrySource; }

        /**
         * 获取安装说明符
         */
        public String getInstallSpecifier() {
            if (skillSubdir != null && !skillSubdir.isEmpty()) {
                return fullName + "/" + skillSubdir;
            }
            return fullName;
        }
    }
}
