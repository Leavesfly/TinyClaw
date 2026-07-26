package io.leavesfly.tinyclaw.channels;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

/**
 * 共享 OkHttpClient 基座。
 *
 * <p>OkHttp 官方推荐整个应用共享一个 OkHttpClient 实例（共享连接池和调度器），
 * 需要不同超时配置时通过 {@code base.newBuilder()} 派生，底层资源自动共享。</p>
 *
 * <p>各 Channel 和 MCP Client 应使用 {@link #get()} 获取基座客户端，
 * 再按需 {@code newBuilder().connectTimeout(...).build()} 派生差异化配置。</p>
 */
public final class SharedHttpClient {

    private SharedHttpClient() {}

    /** 全局共享的 OkHttpClient 基座实例（5 连接 / 5 分钟 keep-alive） */
    private static final OkHttpClient BASE = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * 获取共享基座客户端。
     * 需要自定义超时时使用 {@code SharedHttpClient.get().newBuilder()...build()}。
     */
    public static OkHttpClient get() {
        return BASE;
    }
}
