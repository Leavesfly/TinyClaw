package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.security.SecurityGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件写入工具
 * 
 * 允许Agent向本地文件系统写入内容。
 * 支持创建新文件和覆盖现有文件。
 * 
 * 功能特点：
 * - 自动创建父目录（如果不存在）
 * - 支持写入任意文本内容
 * - 提供明确的成功/失败反馈
 * 
 * 安全考虑：
 * - 目前没有路径限制，未来可添加工作空间限制
 * - 建议在生产环境中限制可写入的目录范围
 * - 应考虑添加文件大小限制防止磁盘填满
 * 
 * 使用场景：
 * - 生成配置文件
 * - 创建代码文件
 * - 保存处理结果
 * - 记录日志信息
 * - 编辑现有文件内容
 */
public class WriteFileTool implements Tool {
    
    private static final long MAX_CONTENT_SIZE_BYTES = 10 * 1024 * 1024; // 10MB
    
    private final SecurityGuard securityGuard;
    
    public WriteFileTool() {
        this.securityGuard = null;
    }
    
    public WriteFileTool(SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
    }
    
    @Override
    public String name() {
        return "write_file";
    }
    
    @Override
    public String description() {
        return "将内容写入文件";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> pathParam = new HashMap<>();
        pathParam.put("type", "string");
        pathParam.put("description", "要写入的文件路径");
        properties.put("path", pathParam);
        
        Map<String, Object> contentParam = new HashMap<>();
        contentParam.put("type", "string");
        contentParam.put("description", "要写入文件的内容");
        properties.put("content", contentParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"path", "content"});
        
        return params;
    }
    
    /**
     * 将路径解析为相对于 workspace 的绝对路径。
     *
     * 当 AI 传入相对路径（如 "foo.md"）时，若直接交给 SecurityGuard 检查，
     * JVM 会将其解析到当前工作目录而非 workspace，导致路径落在 workspace 之外被拦截。
     * 此方法确保相对路径始终基于 workspace 解析。
     */
    private String resolveAgainstWorkspace(String path) {
        if (securityGuard == null || Paths.get(path).isAbsolute()) {
            return path;
        }
        return Paths.get(securityGuard.getWorkspace(), path).normalize().toString();
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String path = (String) args.get("path");
        String content = (String) args.get("content");
        
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("路径参数是必需的");
        }
        if (content == null) {
            throw new IllegalArgumentException("内容参数是必需的");
        }
        
        // 将相对路径解析为相对于 workspace 的绝对路径，防止路径被解析到 JVM 工作目录
        String resolvedPathString = resolveAgainstWorkspace(path);

        // 安全检查
        if (securityGuard != null) {
            String error = securityGuard.checkFilePath(resolvedPathString);
            if (error != null) {
                throw new SecurityException(error);
            }
        }
        
        // 检查内容大小
        long contentBytes = content.getBytes().length;
        if (contentBytes > MAX_CONTENT_SIZE_BYTES) {
            return "写入内容过大（" + contentBytes + " 字节），超过最大限制 " + MAX_CONTENT_SIZE_BYTES + " 字节";
        }
        
        try {
            Path filePath = Paths.get(resolvedPathString);
            Path parentDir = filePath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            Files.writeString(filePath, content);
            return "文件写入成功";
        } catch (IOException e) {
            return "写入文件失败: " + e.getMessage();
        }
    }
}