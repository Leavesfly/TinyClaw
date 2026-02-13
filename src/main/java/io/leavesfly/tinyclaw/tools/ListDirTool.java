package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.security.SecurityGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 目录列表工具
 * 用于查看目录下的文件和子目录
 */
public class ListDirTool implements Tool {
    
    private final SecurityGuard securityGuard;
    
    public ListDirTool() {
        this.securityGuard = null;
    }
    
    public ListDirTool(SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
    }
    
    @Override
    public String name() {
        return "list_dir";
    }
    
    @Override
    public String description() {
        return "列出路径下的文件和目录";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> pathParam = new HashMap<>();
        pathParam.put("type", "string");
        pathParam.put("description", "要列表的路径");
        properties.put("path", pathParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String path = (String) args.get("path");
        if (path == null || path.isEmpty()) {
            path = ".";
        }
        
        // 安全检查
        if (securityGuard != null) {
            String error = securityGuard.checkFilePath(path);
            if (error != null) {
                throw new SecurityException(error);
            }
        }
        
        try {
            Path dirPath = Paths.get(path);
            if (!Files.exists(dirPath)) {
                return "目录不存在: " + path;
            }
            if (!Files.isDirectory(dirPath)) {
                return "路径不是目录: " + path;
            }
            
            StringBuilder result = new StringBuilder();
            Files.list(dirPath).forEach(p -> {
                if (Files.isDirectory(p)) {
                    result.append("DIR:  ").append(p.getFileName()).append("\n");
                } else {
                    result.append("FILE: ").append(p.getFileName()).append("\n");
                }
            });
            
            return result.toString();
        } catch (IOException e) {
            throw new Exception("列表目录失败: " + e.getMessage());
        }
    }
}
