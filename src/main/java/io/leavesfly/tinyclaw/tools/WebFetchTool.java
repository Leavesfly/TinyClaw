package io.leavesfly.tinyclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    
    // 重定向死循环通常源于服务端设置 Cookie 后重定向、再校验 Cookie；
    // 默认 CookieJar.NO_COOKIES 不保存 Cookie，会导致无限重定向直至报 "Too many follow-up requests"。
    private static final int MAX_RETRIES = 2;

    public WebFetchTool(int maxChars) {
        this.maxChars = maxChars > 0 ? maxChars : 50000;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(new InMemoryCookieJar())
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
    public String execute(Map<String, Object> args) throws ToolException {
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
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build();
        
        java.io.IOException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
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
            
            try {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new ToolException("序列化结果失败", e);
            }
            } catch (java.io.InterruptedIOException e) {
                // 连接/读取超时等瞬时问题，重试
                lastError = e;
                logger.warn("获取网页超时，重试中 (" + (attempt + 1) + "/" + (MAX_RETRIES + 1) + "): " + urlStr);
            } catch (java.io.IOException e) {
                throw new ToolException("获取网页内容失败: " + friendlyError(e), e);
            }
        }
        throw new ToolException("获取网页内容失败: " + friendlyError(lastError), lastError);
    }
    
    /**
     * 将底层网络异常转换为更易读的错误信息。
     */
    private String friendlyError(java.io.IOException e) {
        if (e == null) {
            return "未知错误";
        }
        String msg = e.getMessage();
        if (e instanceof java.io.InterruptedIOException) {
            return "连接或读取超时";
        }
        if (e instanceof java.net.ProtocolException && msg != null && msg.contains("Too many follow-up requests")) {
            return "重定向次数过多（可能存在重定向循环）";
        }
        if (e instanceof javax.net.ssl.SSLPeerUnverifiedException || (msg != null && msg.contains("not verified"))) {
            return "SSL 证书校验失败（证书与域名不匹配）: " + msg;
        }
        if (e instanceof java.net.UnknownHostException) {
            return "无法解析域名: " + msg;
        }
        return msg != null ? msg : e.toString();
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
    
    /**
     * 简单的内存 CookieJar，按 host 保存 Cookie。
     * 主要用于在重定向链中保留服务端下发的 Cookie，避免重定向死循环。
     */
    private static final class InMemoryCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> store = new ConcurrentHashMap<>();
        
        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            List<Cookie> existing = store.computeIfAbsent(url.host(), k -> new ArrayList<>());
            synchronized (existing) {
                for (Cookie cookie : cookies) {
                    // 同名 Cookie 覆盖旧值
                    existing.removeIf(c -> c.name().equals(cookie.name()));
                    existing.add(cookie);
                }
            }
        }
        
        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = store.get(url.host());
            if (cookies == null) {
                return Collections.emptyList();
            }
            List<Cookie> valid = new ArrayList<>();
            long now = System.currentTimeMillis();
            synchronized (cookies) {
                for (Cookie c : cookies) {
                    if (c.expiresAt() >= now && c.matches(url)) {
                        valid.add(c);
                    }
                }
            }
            return valid;
        }
    }
}
