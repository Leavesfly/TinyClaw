package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.security.SecurityGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件读取工具
 * 
 * 允许Agent读取本地文件系统中的文件内容。
 * 这是系统中最基础和常用的工具之一。
 * 
 * 功能特点：
 * - 支持读取任意路径的文本文件
 * - 返回完整的文件内容作为字符串
 * - 提供清晰的错误信息处理
 * 
 * 安全考虑：
 * - 目前没有路径限制，未来可添加工作空间限制
 * - 建议在生产环境中限制可访问的目录范围
 * 
 * 使用场景：
 * - 读取配置文件内容
 * - 查看代码文件
 * - 获取文档内容进行分析
 * - 读取数据文件进行处理
 */
public class ReadFileTool implements Tool {
    
    private final SecurityGuard securityGuard;
    
    public ReadFileTool() {
        this.securityGuard = null;
    }
    
    public ReadFileTool(SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
    }
    
    @Override
    public String name() {
        return "read_file";
    }
    
    @Override
    public String description() {
        return "读取文件内容";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> pathParam = new HashMap<>();
        pathParam.put("type", "string");
        pathParam.put("description", "要读取的文件路径");
        properties.put("path", pathParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"path"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String path = (String) args.get("path");
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("路径参数是必需的");
        }
        
        // 安全检查
        if (securityGuard != null) {
            String error = securityGuard.checkFilePath(path);
            if (error != null) {
                throw new SecurityException(error);
            }
        }
        
        try {
            return Files.readString(Paths.get(path));
        } catch (IOException e) {
            throw new Exception("读取文件失败: " + e.getMessage());
        }
    }
}
