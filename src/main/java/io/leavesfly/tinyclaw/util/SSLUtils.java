package io.leavesfly.tinyclaw.util;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * SSL 工具类
 * 
 * 提供信任所有证书的 SSL 配置，用于解决企业内网环境下
 * PKIX 证书链验证失败的问题（如中间代理、自签名证书等）。
 */
public class SSLUtils {
    
    private static final X509TrustManager TRUST_ALL_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }
        
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }
        
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
    
    /**
     * 获取信任所有证书的 TrustManager
     */
    public static X509TrustManager getTrustAllManager() {
        return TRUST_ALL_MANAGER;
    }
    
    /**
     * 获取信任所有证书的 SSLSocketFactory
     */
    public static SSLSocketFactory getTrustAllSSLSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all SSLSocketFactory", e);
        }
    }
    
    /**
     * 获取不验证主机名的 HostnameVerifier
     */
    public static HostnameVerifier getTrustAllHostnameVerifier() {
        return (hostname, session) -> true;
    }
    
    /**
     * 获取使用系统默认证书链的 SSLSocketFactory。
     * 
     * 推荐在访问正规 CA 签发证书的 API（如钉钉、飞书官方 API）时使用，
     * 避免信任所有证书带来的中间人攻击风险。
     * 
     * @return 系统默认的 SSLSocketFactory
     */
    public static SSLSocketFactory getDefaultSSLSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create default SSLSocketFactory", e);
        }
    }
    
    /**
     * 获取系统默认的 X509TrustManager。
     * 
     * 使用系统内置的 CA 证书库进行证书验证。
     * 
     * @return 系统默认的 X509TrustManager
     */
    public static X509TrustManager getDefaultTrustManager() {
        try {
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                    .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
            throw new RuntimeException("No X509TrustManager found in default TrustManagerFactory");
        } catch (Exception e) {
            throw new RuntimeException("Failed to get default X509TrustManager", e);
        }
    }
}
