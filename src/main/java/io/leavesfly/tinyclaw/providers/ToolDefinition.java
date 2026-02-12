package io.leavesfly.tinyclaw.providers;

import java.util.Map;

/**
 * Tool definition for LLM function calling
 */
public class ToolDefinition {
    
    private String type;
    private ToolFunctionDefinition function;
    
    public ToolDefinition() {
        this.type = "function";
    }
    
    public ToolDefinition(String name, String description, Map<String, Object> parameters) {
        this.type = "function";
        this.function = new ToolFunctionDefinition(name, description, parameters);
    }
    
    // Getters and Setters
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public ToolFunctionDefinition getFunction() {
        return function;
    }
    
    public void setFunction(ToolFunctionDefinition function) {
        this.function = function;
    }
    
    /**
     * Tool function definition
     */
    public static class ToolFunctionDefinition {
        private String name;
        private String description;
        private Map<String, Object> parameters;
        
        public ToolFunctionDefinition() {
        }
        
        public ToolFunctionDefinition(String name, String description, Map<String, Object> parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
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
        
        public Map<String, Object> getParameters() {
            return parameters;
        }
        
        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }
    }
}
