package io.leavesfly.tinyclaw.tools;

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
    
    @Override
    public String name() {
        return "write_file";
    }
    
    @Override
    public String description() {
        return "Write content to a file";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> pathParam = new HashMap<>();
        pathParam.put("type", "string");
        pathParam.put("description", "Path to the file to write");
        properties.put("path", pathParam);
        
        Map<String, Object> contentParam = new HashMap<>();
        contentParam.put("type", "string");
        contentParam.put("description", "Content to write to the file");
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
            throw new IllegalArgumentException("path is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        
        try {
            Path filePath = Paths.get(path);
            Path parentDir = filePath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            Files.writeString(filePath, content);
            return "File written successfully";
        } catch (IOException e) {
            throw new Exception("Failed to write file: " + e.getMessage());
        }
    }
}
