package io.leavesfly.tinyclaw.channels;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ChannelManager 发送失败分类测试。
 *
 * <p>分类决定发送策略：超时类不重试（防重复）、4xx 不重试、连接类重试。</p>
 */
@DisplayName("ChannelManager 发送失败分类测试")
class ChannelManagerTest {

    @Test
    @DisplayName("超时异常分类为 UNCERTAIN（不重试防重复）")
    void classify_Timeout_IsUncertain() {
        assertEquals(ChannelManager.SendFailureKind.UNCERTAIN,
                ChannelManager.classifySendFailure(new SocketTimeoutException("timeout")));
        assertEquals(ChannelManager.SendFailureKind.UNCERTAIN,
                ChannelManager.classifySendFailure(new IOException(new SocketTimeoutException("Read timed out"))));
    }

    @Test
    @DisplayName("HTTP 4xx 分类为 FATAL（重试无意义）")
    void classify_Http4xx_IsFatal() {
        assertEquals(ChannelManager.SendFailureKind.FATAL,
                ChannelManager.classifySendFailure(new IOException("HTTP 404: Not Found")));
        assertEquals(ChannelManager.SendFailureKind.FATAL,
                ChannelManager.classifySendFailure(new RuntimeException(new IOException("HTTP 401: Unauthorized"))));
    }

    @Test
    @DisplayName("连接类错误分类为 RETRYABLE")
    void classify_ConnectionError_IsRetryable() {
        assertEquals(ChannelManager.SendFailureKind.RETRYABLE,
                ChannelManager.classifySendFailure(new IOException("Connection reset")));
        assertEquals(ChannelManager.SendFailureKind.RETRYABLE,
                ChannelManager.classifySendFailure(new RuntimeException("socket closed")));
    }
}
