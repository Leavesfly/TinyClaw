package io.leavesfly.tinyclaw.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能加载器 - 用于加载和管理 Agent 技能
 * 
 * <p>SkillsLoader 负责从多个来源加载和管理技能文件，支持工作空间、全局和内置三个层级的技能管理。
 * 技能文件采用 Markdown 格式存储，支持通过 YAML 前置元数据定义技能的名称和描述。</p>
 * 
 * <h2>技能加载优先级：</h2>
 * <ol>
 *   <li>工作空间技能（workspace/skills）- 最高优先级</li>
 *   <li>全局技能（用户配置的全局路径）</li>
 *   <li>内置技能（系统预置的技能）</li>
 * </ol>
 * 
 * <h2>技能文件结构：</h2>
 * <pre>
 * skills/
 *   └── skill-name/
 *       └── SKILL.md
 * </pre>
 * 
 * <h2>SKILL.md 格式示例：</h2>
 * <pre>
 * ---
 * name: "代码生成"
 * description: "根据需求生成高质量的代码"
 * ---
 * 
 * ## 技能说明
 * 
 * 这个技能可以帮助你...
 * </pre>
 * 
 * <h2>主要功能：</h2>
 * <ul>
 *   <li>列出所有可用的技能</li>
 *   <li>按名称加载指定技能</li>
 *   <li>为对话上下文加载多个技能</li>
 *   <li>构建技能摘要（XML 格式）</li>
 *   <li>解析技能元数据</li>
 * </ul>
 * 
 * @author TinyClaw Team
 * @version 0.1.0
 */
public class SkillsLoader {
    
    /** 工作空间根路径 */
    private final String workspace;
    
    /** 工作空间技能目录路径（workspace/skills） */
    private final String workspaceSkills;
    
    /** 全局技能目录路径 */
    private final String globalSkills;
    
    /** 内置技能目录路径 */
    private final String builtinSkills;
    
    /**
     * 构造 SkillsLoader 实例
     * 
     * @param workspace 工作空间根路径
     * @param globalSkills 全局技能目录路径
     * @param builtinSkills 内置技能目录路径
     */
    public SkillsLoader(String workspace, String globalSkills, String builtinSkills) {
        this.workspace = workspace;
        this.workspaceSkills = Paths.get(workspace, "skills").toString();
        this.globalSkills = globalSkills;
        this.builtinSkills = builtinSkills;
    }
    
    /**
     * 列出所有可用的技能
     * 
     * <p>按照优先级顺序从工作空间、全局和内置三个来源加载技能信息。
     * 如果存在同名技能，高优先级的技能会覆盖低优先级的技能。</p>
     * 
     * @return 所有可用技能的列表，按优先级排序
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
        
        // 内置技能
        if (builtinSkills != null) {
            addSkillsFromDir(skills, builtinSkills, "builtin");
        }
        
        return skills;
    }
    
    /**
     * 从指定目录添加技能到列表
     * 
     * <p>遍历目录中的所有子目录，查找包含 SKILL.md 文件的技能。
     * 会检查是否已存在更高优先级的同名技能，避免重复添加。</p>
     * 
     * @param skills 技能列表
     * @param dirPath 要扫描的目录路径
     * @param source 技能来源标识（"workspace"、"global" 或 "builtin"）
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
     * 按名称加载技能
     * 
     * <p>按照优先级顺序（工作空间 > 全局 > 内置）查找并加载指定名称的技能内容。
     * 返回的内容会去除 YAML 前置元数据，只保留实际的 Markdown 内容。</p>
     * 
     * @param name 技能名称
     * @return 技能内容，如果未找到则返回 null
     */
    public String loadSkill(String name) {
        // 优先尝试工作空间技能
        String content = loadSkillFromDir(workspaceSkills, name);
        if (content != null) return content;
        
        // 尝试全局技能
        content = loadSkillFromDir(globalSkills, name);
        if (content != null) return content;
        
        // 尝试内置技能
        content = loadSkillFromDir(builtinSkills, name);
        return content;
    }
    
    /**
     * 从指定目录加载技能
     * 
     * <p>读取指定目录下的技能文件，并去除 YAML 前置元数据。</p>
     * 
     * @param dir 技能目录路径
     * @param name 技能名称
     * @return 处理后的技能内容，失败时返回 null
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
     * 为对话上下文加载多个技能
     * 
     * <p>加载指定名称列表的所有技能，并用分隔线连接，形成完整的技能上下文。
     * 每个技能都会添加标题标识，便于 AI 理解和区分。</p>
     * 
     * @param skillNames 技能名称列表
     * @return 格式化的技能内容字符串，如果没有技能则返回空字符串
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
     * 
     * <p>将所有可用技能的信息构建成 XML 格式的摘要字符串，包含技能名称、描述、位置和来源。
     * 生成的 XML 适用于系统监控、日志记录或外部系统集成。</p>
     * 
     * @return XML 格式的技能摘要，如果没有技能则返回空字符串
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
     * 获取技能元数据
     * 
     * <p>从技能文件中提取 YAML 前置元数据，解析技能的名称和描述信息。
     * 如果没有前置元数据，则使用目录名称作为技能名称。</p>
     * 
     * @param skillPath 技能文件路径
     * @return 技能元数据对象，失败时返回 null
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
     * 提取 YAML 前置元数据
     * 
     * <p>从 Markdown 文件中提取位于 "---" 分隔符之间的 YAML 内容。</p>
     * 
     * @param content 完整的文件内容
     * @return 提取的 YAML 内容，如果没有则返回空字符串
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
     * 
     * <p>从 Markdown 文件内容中移除位于开头的 YAML 前置元数据部分。</p>
     * 
     * @param content 包含前置元数据的完整内容
     * @return 去除前置元数据后的内容
     */
    private String stripFrontmatter(String content) {
        return content.replaceFirst("^---\n.*?\n---\n", "");
    }
    
    /**
     * 解析简单的 YAML 格式
     * 
     * <p>解析形如 "key: value" 的简单 YAML 行，支持引号包裹的值。
     * 这是一个简化的解析器，不支持复杂的 YAML 特性。</p>
     * 
     * @param content YAML 内容字符串
     * @return 键值对映射
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
     * 
     * <p>将字符串中的 XML 特殊字符（&、<、>）转义为对应的实体引用。</p>
     * 
     * @param s 要转义的字符串
     * @return 转义后的字符串，如果输入为 null 则返回空字符串
     */
    private String escapeXML(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
