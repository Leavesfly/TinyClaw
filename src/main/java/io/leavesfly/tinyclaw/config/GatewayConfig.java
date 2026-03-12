package io.leavesfly.tinyclaw.config;

/**
 * 网关配置类
 * 配置 Webhook 服务器的主机地址、端口、认证和安全策略
 */
public class GatewayConfig {
    
    private String host;
    private int port;
    private String username;
    private String password;
    private String corsOrigin;
    private int rateLimitPerMinute;
    
    public GatewayConfig() {
        this.host = "0.0.0.0";
        this.port = 18790;
        this.username = "admin";
        this.password = "tinyclaw";
        this.corsOrigin = "*";
        this.rateLimitPerMinute = 0;
    }
    
    public String getHost() {
        return host;
    }
    
    public void setHost(String host) {
        this.host = host;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getCorsOrigin() {
        return corsOrigin;
    }
    
    public void setCorsOrigin(String corsOrigin) {
        this.corsOrigin = corsOrigin;
    }
    
    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }
    
    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }
    
    /**
     * 检查是否启用了认证。
     * username 和 password 都非空时启用认证。
     */
    public boolean isAuthEnabled() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
    
    /**
     * 检查是否启用了速率限制。
     */
    public boolean isRateLimitEnabled() {
        return rateLimitPerMinute > 0;
    }
}
