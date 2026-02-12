package io.leavesfly.tinyclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Tool for fetching web content
 */
public class WebFetchTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; tinyclaw/1.0)";
    
    private final int maxChars;
    private final OkHttpClient httpClient;
    
    // Patterns to remove from HTML
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_PATTERN = Pattern.compile("<style[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    
    public WebFetchTool(int maxChars) {
        this.maxChars = maxChars > 0 ? maxChars : 50000;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }
    
    @Override
    public String name() {
        return "web_fetch";
    }
    
    @Override
    public String description() {
        return "Fetch a URL and extract readable content (HTML to text). Use this to get weather info, news, articles, or any web content.";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> urlParam = new HashMap<>();
        urlParam.put("type", "string");
        urlParam.put("description", "URL to fetch");
        properties.put("url", urlParam);
        
        Map<String, Object> maxCharsParam = new HashMap<>();
        maxCharsParam.put("type", "integer");
        maxCharsParam.put("description", "Maximum characters to extract");
        maxCharsParam.put("minimum", 100);
        properties.put("maxChars", maxCharsParam);
        
        params.put("properties", properties);
        params.put("required", new String[]{"url"});
        
        return params;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String urlStr = (String) args.get("url");
        if (urlStr == null || urlStr.isEmpty()) {
            throw new IllegalArgumentException("url is required");
        }
        
        // Validate URL
        URI uri;
        try {
            uri = new URI(urlStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + e.getMessage());
        }
        
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Only http/https URLs are allowed");
        }
        
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("Missing domain in URL");
        }
        
        int max = maxChars;
        Object maxObj = args.get("maxChars");
        if (maxObj instanceof Number) {
            int m = ((Number) maxObj).intValue();
            if (m > 100) {
                max = m;
            }
        }
        
        Request request = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", USER_AGENT)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Error: HTTP " + response.code();
            }
            
            String contentType = response.header("Content-Type", "");
            String body = response.body() != null ? response.body().string() : "";
            
            String text;
            String extractor;
            
            if (contentType.contains("application/json")) {
                // JSON content
                try {
                    JsonNode json = objectMapper.readTree(body);
                    text = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
                    extractor = "json";
                } catch (Exception e) {
                    text = body;
                    extractor = "raw";
                }
            } else if (contentType.contains("text/html") || 
                       body.trim().startsWith("<!DOCTYPE") || 
                       body.trim().toLowerCase().startsWith("<html")) {
                // HTML content
                text = extractText(body);
                extractor = "text";
            } else {
                text = body;
                extractor = "raw";
            }
            
            boolean truncated = text.length() > max;
            if (truncated) {
                text = text.substring(0, max);
            }
            
            // 构建 result
            Map<String, Object> result = new HashMap<>();
            result.put("url", urlStr);
            result.put("status", response.code());
            result.put("extractor", extractor);
            result.put("truncated", truncated);
            result.put("length", text.length());
            result.put("text", text);
            
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        }
    }
    
    private String extractText(String html) {
        // Remove script and style tags
        String result = SCRIPT_PATTERN.matcher(html).replaceAll("");
        result = STYLE_PATTERN.matcher(result).replaceAll("");
        
        // Remove all HTML tags
        result = TAG_PATTERN.matcher(result).replaceAll(" ");
        
        // Normalize whitespace
        result = WHITESPACE_PATTERN.matcher(result.trim()).replaceAll(" ");
        
        return result;
    }
}
