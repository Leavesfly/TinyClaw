package io.leavesfly.tinyclaw.skills;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 技能加载器 - 加载和管理 Agent 技能
 * 
 * 从多个来源加载技能文件，支持工作空间、全局和内置三个层级。
 * 技能文件采用 Markdown 格式，通过 YAML 前置元数据定义名称和描述。
 * 
 * 加载优先级：workspace > global > builtin
 */
public class SkillsLoader {
    
    /** 工作空间根路径 */
    private final String workspace;
    
    /** 工作空间技能目录路径（workspace/skills） */
    private final String workspaceSkills;
    
    /** 全局技能目录路径 */
    private final String globalSkills;
    
    /** 内置技能名称列表（从 classpath 加载） */
    private static final List<String> BUILTIN_SKILL_NAMES = Arrays.asList(
            "weather", "github", "summarize", "skill-creator", "tmux"
    );
    
    /** classpath 中内置技能的基础路径 */
    private static final String BUILTIN_SKILLS_PATH = "skills/";
    
    /**
     * 构造 SkillsLoader 实例
     * 
     * @param workspace 工作空间根路径
     * @param globalSkills 全局技能目录路径
     * @param builtinSkills 内置技能目录路径（已废弃，内置技能从 classpath 加载）
     */
    public SkillsLoader(String workspace, String globalSkills, String builtinSkills) {
        this.workspace = workspace;
        this.workspaceSkills = Paths.get(workspace, "skills").toString();
        this.globalSkills = globalSkills;
    }
    
    /**
     * 列出所有可用的技能
     * 
     * 按优先级顺序加载，同名技能高优先级覆盖低优先级
     */
    public List<SkillInfo> listSkills() {
        List<SkillInfo> skills = new ArrayList<>();
        
        // 工作空间技能（最高优先级）
        if (workspaceSkills != null) {
            addSkillsFromDir(skills, workspaceSkills, "workspace");
        }
        
        // 全局技能
        if (globalSkills != null) {
            addSkillsFromDir(skills, globalSkills, "global");
        }
        
        // 内置技能（从 classpath 加载）
        addBuiltinSkills(skills);
        
        return skills;
    }
    
    /**
     * 从 classpath 添加内置技能
     * 
     * @param skills 技能列表
     */
    private void addBuiltinSkills(List<SkillInfo> skills) {
        for (String skillName : BUILTIN_SKILL_NAMES) {
            // 检查是否已存在更高优先级的同名技能
            boolean exists = skills.stream()
                    .anyMatch(s -> s.getName().equals(skillName));
            
            if (!exists) {
                String content = loadBuiltinSkillContent(skillName);
                if (content != null) {
                    SkillInfo info = new SkillInfo();
                    info.setName(skillName);
                    info.setPath("classpath:" + BUILTIN_SKILLS_PATH + skillName + "/SKILL.md");
                    info.setSource("builtin");
                    
                    // 解析元数据
                    String frontmatter = extractFrontmatter(content);
                    if (frontmatter != null && !frontmatter.isEmpty()) {
                        Map<String, String> yaml = parseSimpleYAML(frontmatter);
                        info.setDescription(yaml.getOrDefault("description", ""));
                    }
                    
                    skills.add(info);
                }
            }
        }
    }
    
    /**
     * 从 classpath 加载内置技能内容
     * 
     * @param skillName 技能名称
     * @return 技能内容，失败时返回 null
     */
    private String loadBuiltinSkillContent(String skillName) {
        String resourcePath = BUILTIN_SKILLS_PATH + skillName + "/SKILL.md";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            return null;
        }
    }
    
    /**
     * 从指定目录添加技能到列表
     */
    private void addSkillsFromDir(List<SkillInfo> skills, String dirPath, String source) {
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return;
        
        try {
            Files.list(dir).filter(Files::isDirectory).forEach(skillDir -> {
                String name = skillDir.getFileName().toString();
                Path skillFile = skillDir.resolve("SKILL.md");
                
                if (Files.exists(skillFile)) {
                    // 检查是否已存在更高优先级的同名技能
                    boolean exists = skills.stream()
                            .anyMatch(s -> s.getName().equals(name) && 
                                    (source.equals("builtin") || 
                                     (source.equals("global") && s.getSource().equals("workspace"))));
                    
                    if (!exists) {
                        SkillInfo info = new SkillInfo();
                        info.setName(name);
                        info.setPath(skillFile.toString());
                        info.setSource(source);
                        
                        // 加载元数据
                        SkillMetadata metadata = getSkillMetadata(skillFile);
                        if (metadata != null) {
                            info.setDescription(metadata.getDescription());
                        }
                        
                        skills.add(info);
                    }
                }
            });
        } catch (IOException e) {
            // 忽略读取错误
        }
    }
    
    /**
     * 按名称加载技能，返回去除 YAML 前置元数据后的内容
     */
    public String loadSkill(String name) {
        // 优先尝试工作空间技能
        String content = loadSkillFromDir(workspaceSkills, name);
        if (content != null) return content;
        
        // 尝试全局技能
        content = loadSkillFromDir(globalSkills, name);
        if (content != null) return content;
        
        // 尝试内置技能（从 classpath 加载）
        content = loadBuiltinSkillContent(name);
        if (content != null) {
            return stripFrontmatter(content);
        }
        return null;
    }
    
    /**
     * 从指定目录加载技能
     */
    private String loadSkillFromDir(String dir, String name) {
        if (dir == null) return null;
        
        Path skillFile = Paths.get(dir, name, "SKILL.md");
        if (Files.exists(skillFile)) {
            try {
                String content = Files.readString(skillFile);
                return stripFrontmatter(content);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * 为对话上下文加载多个技能，用分隔线连接
     */
    public String loadSkillsForContext(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) return "";
        
        StringBuilder sb = new StringBuilder();
        for (String name : skillNames) {
            String content = loadSkill(name);
            if (content != null) {
                if (sb.length() > 0) {
                    sb.append("\n\n---\n\n");
                }
                sb.append("### Skill: ").append(name).append("\n\n").append(content);
            }
        }
        return sb.toString();
    }
    
    /**
     * 构建技能摘要（XML 格式）
     */
    public String buildSkillsSummary() {
        List<SkillInfo> allSkills = listSkills();
        if (allSkills.isEmpty()) return "";
        
        StringBuilder sb = new StringBuilder();
        sb.append("<skills>\n");
        
        for (SkillInfo s : allSkills) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXML(s.getName())).append("</name>\n");
            sb.append("    <description>").append(escapeXML(s.getDescription())).append("</description>\n");
            sb.append("    <location>").append(escapeXML(s.getPath())).append("</location>\n");
            sb.append("    <source>").append(s.getSource()).append("</source>\n");
            sb.append("  </skill>\n");
        }
        
        sb.append("</skills>");
        return sb.toString();
    }
    
    /**
     * 获取技能元数据，解析 YAML 前置元数据中的名称和描述
     */
    private SkillMetadata getSkillMetadata(Path skillPath) {
        try {
            String content = Files.readString(skillPath);
            String frontmatter = extractFrontmatter(content);
            
            if (frontmatter == null || frontmatter.isEmpty()) {
                return new SkillMetadata(skillPath.getParent().getFileName().toString());
            }
            
            // 解析简单的 YAML 格式
            Map<String, String> yaml = parseSimpleYAML(frontmatter);
            return new SkillMetadata(
                    yaml.getOrDefault("name", skillPath.getParent().getFileName().toString()),
                    yaml.getOrDefault("description", "")
            );
        } catch (IOException e) {
            return null;
        }
    }
    
    /**
     * 提取 YAML 前置元数据（--- 分隔符之间的内容）
     */
    private String extractFrontmatter(String content) {
        Pattern pattern = Pattern.compile("(?s)^---\n(.*)\n---");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
    
    /**
     * 去除 YAML 前置元数据
     */
    private String stripFrontmatter(String content) {
        return content.replaceFirst("^---\n.*?\n---\n", "");
    }
    
    /**
     * 解析简单的 YAML 格式（key: value）
     */
    private Map<String, String> parseSimpleYAML(String content) {
        Map<String, String> result = new HashMap<>();
        
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                // 移除引号
                value = value.replaceAll("^['\"]|['\"]$", "");
                result.put(key, value);
            }
        }
        
        return result;
    }
    
    /**
     * 转义 XML 特殊字符
     */
    private String escapeXML(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
