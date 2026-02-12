package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆存储 - 持久化的 Agent 记忆管理系统
 * 
 * <p>MemoryStore 提供了长期记忆和每日笔记的持久化存储功能，帮助 AI Agent 保存和检索重要信息。
 * 该系统采用文件系统存储，支持 Markdown 格式，便于人工查看和编辑。</p>
 * 
 * <h2>存储结构：</h2>
 * <ul>
 *   <li>长期记忆：memory/MEMORY.md - 存储持久化的知识和上下文</li>
 *   <li>每日笔记：memory/YYYYMM/YYYYMMDD.md - 按日期组织的日常记录</li>
 * </ul>
 * 
 * <h2>主要功能：</h2>
 * <ul>
 *   <li>长期记忆的读写操作</li>
 *   <li>每日笔记的创建和追加</li>
 *   <li>获取最近 N 天的笔记</li>
 *   <li>构建完整的记忆上下文供 AI 使用</li>
 * </ul>
 * 
 * <h2>使用示例：</h2>
 * <pre>
 * // 创建记忆存储
 * MemoryStore memoryStore = new MemoryStore("/path/to/workspace");
 * 
 * // 写入长期记忆
 * memoryStore.writeLongTerm("用户偏好：喜欢简洁的回答，关注技术细节");
 * 
 * // 追加今日笔记
 * memoryStore.appendToday("完成了系统升级任务");
 * 
 * // 获取完整记忆上下文
 * String context = memoryStore.getMemoryContext();
 * </pre>
 * 
 * @author TinyClaw Team
 * @version 0.1.0
 */
public class MemoryStore {
    
    /** 记忆存储专用日志记录器 */
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("memory");
    
    /** 工作空间根路径 */
    private final String workspace;
    
    /** 记忆存储目录路径（workspace/memory） */
    private final String memoryDir;
    
    /** 长期记忆文件路径（memory/MEMORY.md） */
    private final String memoryFile;
    
    /**
     * 构造 MemoryStore 实例
     * 
     * <p>初始化记忆存储系统，创建必要的目录结构。如果目录创建失败会记录警告日志。</p>
     * 
     * @param workspace 工作空间根路径
     */
    public MemoryStore(String workspace) {
        this.workspace = workspace;
        this.memoryDir = Paths.get(workspace, "memory").toString();
        this.memoryFile = Paths.get(memoryDir, "MEMORY.md").toString();
        
        // 确保记忆目录存在
        try {
            Files.createDirectories(Paths.get(memoryDir));
        } catch (IOException e) {
            logger.warn("Failed to create memory directory: " + e.getMessage());
        }
    }
    
    /**
     * 获取今日笔记文件的路径
     * 
     * <p>根据当前日期构建笔记文件路径，格式为 memory/YYYYMM/YYYYMMDD.md。
     * 例如：2025年2月12日的文件路径为 memory/202502/20250212.md</p>
     * 
     * @return 今日笔记文件的完整路径
     */
    private String getTodayFile() {
        LocalDate today = LocalDate.now();
        String dateStr = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String monthDir = dateStr.substring(0, 6); // YYYYMM
        return Paths.get(memoryDir, monthDir, dateStr + ".md").toString();
    }
    
    /**
     * 读取长期记忆文件
     * 
     * <p>从 memory/MEMORY.md 文件中读取持久化的长期记忆内容。
     * 如果文件不存在或读取失败，返回空字符串。</p>
     * 
     * @return 长期记忆内容，失败时返回空字符串
     */
    public String readLongTerm() {
        try {
            if (Files.exists(Paths.get(memoryFile))) {
                return Files.readString(Paths.get(memoryFile));
            }
        } catch (IOException e) {
            logger.warn("Failed to read long-term memory: " + e.getMessage());
        }
        return "";
    }
    
    /**
     * 写入长期记忆文件
     * 
     * <p>将内容完整写入 memory/MEMORY.md 文件，覆盖原有内容。
     * 写入成功后会记录调试日志。</p>
     * 
     * @param content 要写入的长期记忆内容
     */
    public void writeLongTerm(String content) {
        try {
            Files.writeString(Paths.get(memoryFile), content);
            logger.debug("Wrote long-term memory");
        } catch (IOException e) {
            logger.error("Failed to write long-term memory", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 读取今日笔记
     * 
     * <p>从今日的笔记文件中读取内容。如果文件不存在或读取失败，返回空字符串。</p>
     * 
     * @return 今日笔记内容，失败时返回空字符串
     */
    public String readToday() {
        String todayFile = getTodayFile();
        try {
            if (Files.exists(Paths.get(todayFile))) {
                return Files.readString(Paths.get(todayFile));
            }
        } catch (IOException e) {
            logger.warn("Failed to read today's note: " + e.getMessage());
        }
        return "";
    }
    
    /**
     * 追加内容到今日笔记
     * 
     * <p>将内容追加到今日笔记文件的末尾。如果文件不存在，会创建新文件并添加日期标题。
     * 如果月份目录不存在，会自动创建。</p>
     * 
     * @param content 要追加的内容
     */
    public void appendToday(String content) {
        String todayFile = getTodayFile();
        
        try {
            // 确保月份目录存在
            Path monthDirPath = Paths.get(todayFile).getParent();
            if (monthDirPath != null) {
                Files.createDirectories(monthDirPath);
            }
            
            // 读取现有内容
            String existingContent = "";
            if (Files.exists(Paths.get(todayFile))) {
                existingContent = Files.readString(Paths.get(todayFile));
            }
            
            // 构建新内容
            String newContent;
            if (existingContent.isEmpty()) {
                // 为新的一天添加标题
                String header = "# " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "\n\n";
                newContent = header + content;
            } else {
                newContent = existingContent + "\n" + content;
            }
            
            // 写入文件
            Files.writeString(Paths.get(todayFile), newContent);
            logger.debug("Appended to today's note");
        } catch (IOException e) {
            logger.error("Failed to append to today's note", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 获取最近 N 天的每日笔记
     * 
     * <p>从今天开始往前推算 N 天，收集所有存在的笔记文件内容。
     * 每天的笔记之间用分隔线 "---" 分隔。</p>
     * 
     * @param days 要获取的天数
     * @return 合并后的笔记内容，如果没有笔记则返回空字符串
     */
    public String getRecentDailyNotes(int days) {
        List<String> notes = new ArrayList<>();
        LocalDate today = LocalDate.now();
        
        // 遍历最近 N 天
        for (int i = 0; i < days; i++) {
            LocalDate date = today.minusDays(i);
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String monthDir = dateStr.substring(0, 6);
            String filePath = Paths.get(memoryDir, monthDir, dateStr + ".md").toString();
            
            try {
                if (Files.exists(Paths.get(filePath))) {
                    notes.add(Files.readString(Paths.get(filePath)));
                }
            } catch (IOException e) {
                // 忽略单个笔记的读取错误
            }
        }
        
        if (notes.isEmpty()) {
            return "";
        }
        
        // 用分隔线连接所有笔记
        return String.join("\n\n---\n\n", notes);
    }
    
    /**
     * 获取格式化的记忆上下文，用于 Agent 提示
     * 
     * <p>构建完整的记忆上下文字符串，包含长期记忆和最近 3 天的每日笔记。
     * 返回的格式为 Markdown，便于 AI 理解和使用。</p>
     * 
     * @return 格式化的记忆上下文，如果没有记忆则返回空字符串
     */
    public String getMemoryContext() {
        List<String> parts = new ArrayList<>();
        
        // 添加长期记忆
        String longTerm = readLongTerm();
        if (StringUtils.isNotBlank(longTerm)) {
            parts.add("## Long-term Memory\n\n" + longTerm);
        }
        
        // 添加最近 3 天的每日笔记
        String recentNotes = getRecentDailyNotes(3);
        if (StringUtils.isNotBlank(recentNotes)) {
            parts.add("## Recent Daily Notes\n\n" + recentNotes);
        }
        
        if (parts.isEmpty()) {
            return "";
        }
        
        // 组合所有部分
        return "# Memory\n\n" + String.join("\n\n---\n\n", parts);
    }
}