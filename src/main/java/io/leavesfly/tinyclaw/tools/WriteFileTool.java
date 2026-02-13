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
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String path = (String) args.get("path");
        String content = (String) args.get("content");
        
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("路径参数是必需的");
        }
        if (content == null) {
            throw new IllegalArgumentException("内容参数是必需的");
        }
        
        // 安全检查
        if (securityGuard != null) {
            String error = securityGuard.checkFilePath(path);
            if (error != null) {
                throw new SecurityException(error);
            }
        }
        
        try {
            Path filePath = Paths.get(path);
            Path parentDir = filePath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            Files.writeString(filePath, content);
            return "文件写入成功";
        } catch (IOException e) {
            throw new Exception("写入文件失败: " + e.getMessage());
        }
    }
}
