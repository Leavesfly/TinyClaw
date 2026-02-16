package io.leavesfly.tinyclaw.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器信息
 * 
 * 存储 MCP Server 的元信息和能力
 */
public class MCPServerInfo {
    
    private String name;
    private String version;
    private String protocolVersion;
    private Map<String, Object> capabilities;
    private List<ToolInfo> tools;
    private List<ResourceInfo> resources;
    private List<PromptInfo> prompts;
    
    public MCPServerInfo() {
        this.capabilities = new HashMap<>();
        this.tools = new ArrayList<>();
        this.resources = new ArrayList<>();
        this.prompts = new ArrayList<>();
    }
    
    // Getters and Setters
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public String getProtocolVersion() {
        return protocolVersion;
    }
    
    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }
    
    public Map<String, Object> getCapabilities() {
        return capabilities;
    }
    
    public void setCapabilities(Map<String, Object> capabilities) {
        this.capabilities = capabilities;
    }
    
    public List<ToolInfo> getTools() {
        return tools;
    }
    
    public void setTools(List<ToolInfo> tools) {
        this.tools = tools;
    }
    
    public List<ResourceInfo> getResources() {
        return resources;
    }
    
    public void setResources(List<ResourceInfo> resources) {
        this.resources = resources;
    }
    
    public List<PromptInfo> getPrompts() {
        return prompts;
    }
    
    public void setPrompts(List<PromptInfo> prompts) {
        this.prompts = prompts;
    }
    
    /**
     * 工具信息
     */
    public static class ToolInfo {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
        
        public ToolInfo() {
        }
        
        public ToolInfo(String name, String description, Map<String, Object> inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public Map<String, Object> getInputSchema() {
            return inputSchema;
        }
        
        public void setInputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema;
        }
    }
    
    /**
     * 资源信息
     */
    public static class ResourceInfo {
        private String uri;
        private String name;
        private String description;
        private String mimeType;
        
        public ResourceInfo() {
        }
        
        public String getUri() {
            return uri;
        }
        
        public void setUri(String uri) {
            this.uri = uri;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public String getMimeType() {
            return mimeType;
        }
        
        public void setMimeType(String mimeType) {
            this.mimeType = mimeType;
        }
    }
    
    /**
     * 提示词模板信息
     */
    public static class PromptInfo {
        private String name;
        private String description;
        private List<ArgumentInfo> arguments;
        
        public PromptInfo() {
            this.arguments = new ArrayList<>();
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public List<ArgumentInfo> getArguments() {
            return arguments;
        }
        
        public void setArguments(List<ArgumentInfo> arguments) {
            this.arguments = arguments;
        }
    }
    
    /**
     * 参数信息
     */
    public static class ArgumentInfo {
        private String name;
        private String description;
        private boolean required;
        
        public ArgumentInfo() {
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public boolean isRequired() {
            return required;
        }
        
        public void setRequired(boolean required) {
            this.required = required;
        }
    }
}
