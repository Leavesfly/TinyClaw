package io.leavesfly.tinyclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tool for web searching using Brave Search API
 */
public class WebSearchTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String apiKey;
    private final int maxResults;
    private final OkHttpClient httpClient;
    
    public WebSearchTool(String apiKey, int maxResults) {
        this.apiKey = apiKey;
        this.maxResults = maxResults > 0 && maxResults <= 10 ? maxResults : 5;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }
    
    @Override
    public String name() {
        return "web_search";
    }
    
    @Override
    public String description() {
        return "Search the web for current information. Returns titles, URLs, and snippets from search results.";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> queryParam = new HashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "Search query");
        properties.put("query", queryParam);
        
        Map<String, Object> countParam = new HashMap<>();
        countParam.put("type", "integer");
        countParam.put("description", "Number of results (1-10)");
        countParam.put("minimum", 1);
        countParam.put("maximum", 10);
        properties.put("count", countParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"query"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            return "Error: BRAVE_API_KEY not configured";
        }
        
        String query = (String) args.get("query");
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }
        
        int count = maxResults;
        Object countObj = args.get("count");
        if (countObj instanceof Number) {
            int c = ((Number) countObj).intValue();
            if (c > 0 && c <= 10) {
                count = c;
            }
        }
        
        String url = String.format(
                "https://api.search.brave.com/res/v1/web/search?q=%s&count=%d",
                URLEncoder.encode(query, StandardCharsets.UTF_8),
                count
        );
        
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Error: Search API returned status " + response.code();
            }
            
            String body = response.body() != null ? response.body().string() : "{}";
            JsonNode root = objectMapper.readTree(body);
            
            StringBuilder result = new StringBuilder();
            result.append("Results for: ").append(query).append("\n");
            
            JsonNode webResults = root.path("web").path("results");
            if (webResults.isArray()) {
                int i = 1;
                for (JsonNode item : webResults) {
                    if (i > count) break;
                    
                    String title = item.path("title").asText("");
                    String itemUrl = item.path("url").asText("");
                    String description = item.path("description").asText("");
                    
                    result.append(i).append(". ").append(title).append("\n");
                    result.append("   ").append(itemUrl).append("\n");
                    if (!description.isEmpty()) {
                        result.append("   ").append(description).append("\n");
                    }
                    i++;
                }
            }
            
            return result.toString();
        }
    }
}
