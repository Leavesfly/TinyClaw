package io.leavesfly.tinyclaw.providers;

import java.util.List;

/**
 * LLM response
 */
public class LLMResponse {
    
    private String content;
    private List<ToolCall> toolCalls;
    private String finishReason;
    private UsageInfo usage;
    
    public LLMResponse() {
    }
    
    public LLMResponse(String content) {
        this.content = content;
        this.finishReason = "stop";
    }
    
    // Getters and Setters
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }
    
    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }
    
    public String getFinishReason() {
        return finishReason;
    }
    
    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }
    
    public UsageInfo getUsage() {
        return usage;
    }
    
    public void setUsage(UsageInfo usage) {
        this.usage = usage;
    }
    
    /**
     * 检查 if the response contains tool calls
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
    
    /**
     * Usage information
     */
    public static class UsageInfo {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        
        public UsageInfo() {
        }
        
        public UsageInfo(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
        
        public int getPromptTokens() {
            return promptTokens;
        }
        
        public void setPromptTokens(int promptTokens) {
            this.promptTokens = promptTokens;
        }
        
        public int getCompletionTokens() {
            return completionTokens;
        }
        
        public void setCompletionTokens(int completionTokens) {
            this.completionTokens = completionTokens;
        }
        
        public int getTotalTokens() {
            return totalTokens;
        }
        
        public void setTotalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
