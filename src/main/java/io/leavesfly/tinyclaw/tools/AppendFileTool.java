package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.security.SecurityGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件追加工具 - 向文件末尾追加内容
 * 
 * 这个工具允许Agent在现有文件末尾添加新内容，而不会覆盖原有内容。
 * 这在需要逐步累积信息时非常有用。
 * 
 * 核心功能：
 * - 追加模式：在文件末尾添加内容，保留原有内容
 * - 自动创建：如果文件不存在，会自动创建新文件
 * - 原子操作：确保写入的完整性
 * 
 * 与 write_file 和 edit_file 的区别：
 * - write_file：覆盖整个文件内容
 * - edit_file：精确替换文件中的特定文本
 * - append_file：在文件末尾追加内容
 * 
 * 安全特性：
 * - 路径规范化处理
 * - 自动创建父目录
 * - 支持目录限制配置
 * 
 * 使用场景：
 * - 添加日志条目
 * - 追加数据记录
 * - 扩展列表内容
 * - 更新每日笔记
 * - 添加记忆条目
 */
public class AppendFileTool implements Tool {
    
    private final SecurityGuard securityGuard;
    // 已废弃：使用 SecurityGuard 代替
    private final String allowedDir;
    
    /**
     * 创建无目录限制的追加工具
     */
    public AppendFileTool() {
        this.securityGuard = null;
        this.allowedDir = null;
    }
    
    /**
     * 创建带 SecurityGuard 的追加工具
     */
    public AppendFileTool(SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
        this.allowedDir = null;
    }
    
    /**
     * 创建带目录限制的追加工具（已废弃：使用 SecurityGuard 代替）
     * 
     * @param allowedDir 允许追加的目录路径
     */
    @Deprecated
    public AppendFileTool(String allowedDir) {
        this.securityGuard = null;
        this.allowedDir = allowedDir;
    }
    
    @Override
    public String name() {
        return "append_file";
    }
    
    @Override
    public String description() {
        return "在文件末尾追加内容";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> pathParam = new HashMap<>();
        pathParam.put("type", "string");
        pathParam.put("description", "要追加内容的文件路径");
        properties.put("path", pathParam);
        
        Map<String, Object> contentParam = new HashMap<>();
        contentParam.put("type", "string");
        contentParam.put("description", "要追加的内容");
        properties.put("content", contentParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"path", "content"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        // 参数验证
        String path = (String) args.get("path");
        String content = (String) args.get("content");
        
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        
        // 解析并规范化路径
        Path resolvedPath = Paths.get(path).toAbsolutePath().normalize();
        
        // 使用 SecurityGuard 进行安全检查（推荐）
        if (securityGuard != null) {
            String error = securityGuard.checkFilePath(path);
            if (error != null) {
                throw new SecurityException(error);
            }
        }
        // 使用 allowedDir 进行旧式检查
        else if (allowedDir != null && !allowedDir.isEmpty()) {
            Path allowedPath = Paths.get(allowedDir).toAbsolutePath().normalize();
            if (!resolvedPath.startsWith(allowedPath)) {
                throw new SecurityException("路径 " + path + " 在允许目录 " + allowedDir + " 之外");
            }
        }
        
        // 确保父目录存在
        Path parentDir = resolvedPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                throw new Exception("创建父目录失败: " + e.getMessage());
            }
        }
        
        // 追加内容到文件
        // 如果文件不存在，CREATE 选项会创建新文件
        // 如果文件存在，APPEND 选项会在末尾追加
        try {
            Files.writeString(resolvedPath, content, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.APPEND);
            return "成功追加内容到 " + path;
        } catch (IOException e) {
            throw new Exception("追加内容到文件失败: " + e.getMessage());
        }
    }
}
