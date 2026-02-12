package io.leavesfly.tinyclaw.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Tool for listing directory contents
 */
public class ListDirTool implements Tool {
    
    @Override
    public String name() {
        return "list_dir";
    }
    
    @Override
    public String description() {
        return "List files and directories in a path";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> pathParam = new HashMap<>();
        pathParam.put("type", "string");
        pathParam.put("description", "Path to list");
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
        
        try {
            Path dirPath = Paths.get(path);
            if (!Files.exists(dirPath)) {
                return "Directory does not exist: " + path;
            }
            if (!Files.isDirectory(dirPath)) {
                return "Path is not a directory: " + path;
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
            throw new Exception("Failed to list directory: " + e.getMessage());
        }
    }
}
