package io.leavesfly.tinyclaw.config;

/**
 * Tools configuration
 */
public class ToolsConfig {
    
    private WebToolsConfig web;
    
    public ToolsConfig() {
        this.web = new WebToolsConfig();
    }
    
    public WebToolsConfig getWeb() {
        return web;
    }
    
    public void setWeb(WebToolsConfig web) {
        this.web = web;
    }
    
    /**
     * 获取 Brave API key for web search
     */
    public String getBraveApi() {
        return web != null && web.getSearch() != null ? web.getSearch().getApiKey() : "";
    }
    
    public static class WebToolsConfig {
        private WebSearchConfig search;
        
        public WebToolsConfig() {
            this.search = new WebSearchConfig();
        }
        
        public WebSearchConfig getSearch() {
            return search;
        }
        
        public void setSearch(WebSearchConfig search) {
            this.search = search;
        }
    }
    
    public static class WebSearchConfig {
        private String apiKey;
        private int maxResults;
        
        public WebSearchConfig() {
            this.apiKey = "";
            this.maxResults = 5;
        }
        
        public String getApiKey() {
            return apiKey;
        }
        
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
        
        public int getMaxResults() {
            return maxResults;
        }
        
        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }
    }
}
