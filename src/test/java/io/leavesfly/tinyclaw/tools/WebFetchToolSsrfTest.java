package io.leavesfly.tinyclaw.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebFetchTool SSRF 防护单元测试
 *
 * <p>验证私有/保留 IP 地址的拦截逻辑（纯校验，不发真实网络请求）。</p>
 */
@DisplayName("WebFetchTool SSRF 防护测试")
class WebFetchToolSsrfTest {

    // ==================== isPrivateAddress 字面量 IP 检查 ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",       // loopback
            "10.0.0.1",        // site-local 10/8
            "172.16.0.1",      // site-local 172.16/12
            "192.168.1.1",     // site-local 192.168/16
            "169.254.169.254", // link-local (cloud metadata)
            "0.0.0.0",         // any local
    })
    @DisplayName("isPrivateAddress: 私有/保留 IPv4 地址被拦截")
    void isPrivateAddress_PrivateIpv4_Blocked(String ip) {
        assertTrue(WebFetchTool.isPrivateAddress(ip), ip + " 应被拦截");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "::1",             // IPv6 loopback
            "fe80::1",         // IPv6 link-local
            "fc00::1",         // IPv6 unique-local
    })
    @DisplayName("isPrivateAddress: 私有/保留 IPv6 地址被拦截")
    void isPrivateAddress_PrivateIpv6_Blocked(String ip) {
        assertTrue(WebFetchTool.isPrivateAddress(ip), ip + " 应被拦截");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8",         // Google DNS
            "1.1.1.1",         // Cloudflare DNS
            "93.184.216.34",   // example.com
    })
    @DisplayName("isPrivateAddress: 公网 IP 地址放行")
    void isPrivateAddress_PublicIp_Allowed(String ip) {
        assertFalse(WebFetchTool.isPrivateAddress(ip), ip + " 应被放行");
    }

    @Test
    @DisplayName("isPrivateAddress: 无法解析的域名不拦截（交由 SafeDns 处理）")
    void isPrivateAddress_UnresolvableHost_NotBlocked() {
        assertFalse(WebFetchTool.isPrivateAddress("this-domain-does-not-exist-xyz.invalid"));
    }

    // ==================== isBlockedInetAddress 详细检查 ====================

    @Test
    @DisplayName("isBlockedInetAddress: 组播地址被拦截")
    void isBlockedInetAddress_Multicast_Blocked() throws UnknownHostException {
        InetAddress multicast = InetAddress.getByName("224.0.0.1");
        assertTrue(WebFetchTool.isBlockedInetAddress(multicast));
    }

    @Test
    @DisplayName("isBlockedInetAddress: 公网地址放行")
    void isBlockedInetAddress_Public_Allowed() throws UnknownHostException {
        InetAddress publicAddr = InetAddress.getByName("8.8.8.8");
        assertFalse(WebFetchTool.isBlockedInetAddress(publicAddr));
    }

    // ==================== execute 入口 SSRF 拦截 ====================

    @Test
    @DisplayName("execute: 字面量私有 IP URL 被拒绝")
    void execute_PrivateIpUrl_Rejected() {
        WebFetchTool tool = new WebFetchTool(1000);
        var args = java.util.Map.<String, Object>of("url", "http://169.254.169.254/latest/meta-data/");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> tool.execute(args));
        assertTrue(ex.getMessage().contains("禁止访问内网/保留地址"));
    }

    @Test
    @DisplayName("execute: localhost URL 被拒绝")
    void execute_LocalhostUrl_Rejected() {
        WebFetchTool tool = new WebFetchTool(1000);
        var args = java.util.Map.<String, Object>of("url", "http://127.0.0.1:18790/api/config");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> tool.execute(args));
        assertTrue(ex.getMessage().contains("禁止访问内网/保留地址"));
    }

    @Test
    @DisplayName("execute: 非 http/https 协议被拒绝")
    void execute_NonHttpScheme_Rejected() {
        WebFetchTool tool = new WebFetchTool(1000);
        var args = java.util.Map.<String, Object>of("url", "file:///etc/passwd");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> tool.execute(args));
        assertTrue(ex.getMessage().contains("只允许 http/https"));
    }
}
