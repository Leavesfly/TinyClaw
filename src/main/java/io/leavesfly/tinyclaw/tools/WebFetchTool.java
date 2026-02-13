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
 * 网页内容获取工具
 * 用于获取网页内容并提取可读文本
 */
public class WebFetchTool implements Tool {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; tinyclaw/1.0)";
    
    private final int maxChars;
    private final OkHttpClient httpClient;
    
    // 从 HTML 中移除的模式
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
        return "获取 URL 并提取可读内容（HTML 转文本）。用于获取天气信息、新闻、文章或任何网页内容。";
    }
    
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> urlParam = new HashMap<>();
        urlParam.put("type", "string");
        urlParam.put("description", "要获取的 URL");
        properties.put("url", urlParam);
        
        Map<String, Object> maxCharsParam = new HashMap<>();
        maxCharsParam.put("type", "integer");
        maxCharsParam.put("description", "最大提取字符数");
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
            throw new IllegalArgumentException("url 参数是必需的");
        }
        
        // 验证 URL
        URI uri;
        try {
            uri = new URI(urlStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的 URL: " + e.getMessage());
        }
        
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("只允许 http/https URL");
        }
        
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("URL 中缺少域名");
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
                return "错误: HTTP " + response.code();
            }
            
            String contentType = response.header("Content-Type", "");
            String body = response.body() != null ? response.body().string() : "";
            
            String text;
            String extractor;
            
            if (contentType.contains("application/json")) {
                // JSON 内容
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
                // HTML 内容
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
            
            // 构建结果
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
        // 移除 script 和 style 标签
        String result = SCRIPT_PATTERN.matcher(html).replaceAll("");
        result = STYLE_PATTERN.matcher(result).replaceAll("");
        
        // 移除所有 HTML 标签
        result = TAG_PATTERN.matcher(result).replaceAll(" ");
        
        // 规范化空白字符
        result = WHITESPACE_PATTERN.matcher(result.trim()).replaceAll(" ");
        
        return result;
    }
}
