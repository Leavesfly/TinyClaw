package io.leavesfly.tinyclaw.channels;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 通用令牌生命周期管理器，支持自动获取、过期检测、线程安全刷新。
 * 适用于 Feishu、DingTalk、QQ 等需要 OAuth token 的通道。
 */
public class TokenManager {
    
    /** 提前刷新缓冲时间（秒） */
    private static final int EXPIRE_BUFFER_SECONDS = 30;
    
    /** 当前访问令牌 */
    private volatile String accessToken;
    
    /** 令牌过期时间（毫秒时间戳） */
    private volatile long tokenExpireTime;
    
    /** 刷新锁，保证线程安全 */
    private final ReentrantLock refreshLock = new ReentrantLock();
    
    /** 令牌刷新器 */
    private final TokenRefresher refresher;
    
    /**
     * 令牌刷新器函数式接口
     */
    @FunctionalInterface
    public interface TokenRefresher {
        /**
         * 执行实际的 token 获取
         * @return 令牌结果
         * @throws Exception 获取失败时抛出异常
         */
        TokenResult refresh() throws Exception;
    }
    
    /**
     * 令牌结果记录
     * @param token 访问令牌
     * @param expiresInSeconds 有效期（秒）
     */
    public record TokenResult(String token, int expiresInSeconds) {}
    
    /**
     * 创建令牌管理器
     * @param refresher 令牌刷新器
     */
    public TokenManager(TokenRefresher refresher) {
        this.refresher = refresher;
    }
    
    /**
     * 获取有效的访问令牌。如果令牌已过期或即将过期，自动刷新。
     * 
     * @return 有效的访问令牌
     * @throws Exception 刷新令牌失败时抛出异常
     */
    public String getValidToken() throws Exception {
        if (!isExpired()) {
            return accessToken;
        }
        
        refreshLock.lock();
        try {
            // 双重检查，避免重复刷新
            if (!isExpired()) {
                return accessToken;
            }
            
            TokenResult result = refresher.refresh();
            if (result == null || result.token() == null) {
                throw new ChannelException("Token refresh returned null");
            }
            
            this.accessToken = result.token();
            // 计算过期时间，提前 EXPIRE_BUFFER_SECONDS 秒刷新
            this.tokenExpireTime = System.currentTimeMillis() + 
                    (result.expiresInSeconds() - EXPIRE_BUFFER_SECONDS) * 1000L;
            
            return accessToken;
        } finally {
            refreshLock.unlock();
        }
    }
    
    /**
     * 强制使当前令牌失效，下次 getValidToken() 调用将刷新令牌
     */
    public void invalidate() {
        refreshLock.lock();
        try {
            this.accessToken = null;
            this.tokenExpireTime = 0;
        } finally {
            refreshLock.unlock();
        }
    }
    
    /**
     * 检查令牌是否已过期或即将过期
     * 
     * @return true 表示已过期需要刷新
     */
    public boolean isExpired() {
        return accessToken == null || System.currentTimeMillis() >= tokenExpireTime;
    }
    
    /**
     * 获取当前令牌（不检查过期，不自动刷新）
     * 
     * @return 当前令牌，可能为 null
     */
    public String getCurrentToken() {
        return accessToken;
    }
}
