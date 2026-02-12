package io.leavesfly.tinyclaw.config;

/**
 * Gateway configuration
 */
public class GatewayConfig {
    
    private String host;
    private int port;
    
    public GatewayConfig() {
        this.host = "0.0.0.0";
        this.port = 18790;
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
}
