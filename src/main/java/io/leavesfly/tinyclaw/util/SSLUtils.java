package io.leavesfly.tinyclaw.util;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * SSL 工具类
 * 
 * 提供 SSL 配置工具方法，包括：
 * - 系统默认证书验证（推荐生产环境使用）
 * - 信任所有证书（仅限开发/内网环境，存在中间人攻击风险）
 * 
 * ⚠️ 安全警告：trust-all 模式会跳过所有证书验证，仅应在以下场景使用：
 * - 企业内网环境（中间代理、自签名证书）
 * - 本地开发调试
 * 生产环境务必使用 getDefaultSSLSocketFactory() / getDefaultTrustManager()。
 */
public class SSLUtils {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("ssl");
    
    /**
     * 信任所有证书的 TrustManager。
     * 
     * ⚠️ 安全风险：此 TrustManager 不验证任何证书，存在中间人攻击风险。
     * 仅限开发环境或企业内网使用。
     */
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
     * 获取信任所有证书的 TrustManager。
     * 
     * ⚠️ 安全风险：仅限开发环境或企业内网使用。
     * 生产环境请使用 {@link #getDefaultTrustManager()}。
     */
    public static X509TrustManager getTrustAllManager() {
        logger.warn("Using trust-all TrustManager - certificates will NOT be verified. " +
                "This is insecure and should only be used in dev/intranet environments.");
        return TRUST_ALL_MANAGER;
    }
    
    /**
     * 获取信任所有证书的 SSLSocketFactory。
     * 
     * ⚠️ 安全风险：仅限开发环境或企业内网使用。
     * 生产环境请使用 {@link #getDefaultSSLSocketFactory()}。
     */
    public static SSLSocketFactory getTrustAllSSLSocketFactory() {
        logger.warn("Using trust-all SSLSocketFactory - certificates will NOT be verified. " +
                "This is insecure and should only be used in dev/intranet environments.");
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all SSLSocketFactory", e);
        }
    }
    
    /**
     * 获取不验证主机名的 HostnameVerifier。
     * 
     * ⚠️ 安全风险：仅限开发环境或企业内网使用。
     */
    public static HostnameVerifier getTrustAllHostnameVerifier() {
        logger.warn("Using trust-all HostnameVerifier - hostname will NOT be verified.");
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
