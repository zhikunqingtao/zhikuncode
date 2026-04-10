package com.aicodeassistant.hook;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * SSRF 防护 — 对齐原版 ssrfGuard.ts（295行）。
 * <p>
 * 阻断 HTTP Hook 到达内网/云元数据端点。
 * 回环地址（127.0.0.0/8, ::1）刻意允许 — 本地开发服务器是主要用例。
 * <p>
 * ★ 关键架构决策: 使用自定义 DnsResolver 将 DNS 解析和连接绑定为原子操作，
 * 消除了 TOCTOU DNS rebinding 窗口。
 *
 * @see <a href="SPEC §11.5.4A">SsrfGuard 规格</a>
 */
public class SsrfGuard {

    /**
     * 检查 IP 地址是否在阻断范围内。
     * 阻断: 0.0.0.0/8, 10.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12,
     *       100.64.0.0/10 (CGNAT), 192.168.0.0/16
     * 允许: 127.0.0.0/8 (回环)
     */
    public static boolean isBlockedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isBlockedV4(bytes);
        if (bytes.length == 16) return isBlockedV6(bytes);
        return false;
    }

    private static boolean isBlockedV4(byte[] b) {
        int a = b[0] & 0xFF, second = b[1] & 0xFF;
        if (a == 127) return false; // 回环允许
        if (a == 0) return true;     // 0.0.0.0/8
        if (a == 10) return true;    // 10.0.0.0/8
        if (a == 169 && second == 254) return true; // 169.254.0.0/16
        if (a == 172 && second >= 16 && second <= 31) return true; // 172.16.0.0/12
        if (a == 100 && second >= 64 && second <= 127) return true; // 100.64.0.0/10
        if (a == 192 && second == 168) return true; // 192.168.0.0/16
        return false;
    }

    private static boolean isBlockedV6(byte[] b) {
        // ::1 回环允许
        if (isIPv6Loopback(b)) return false;
        // :: 未指定地址
        if (isAllZeros(b)) return true;
        // IPv4-mapped IPv6 (::ffff:x.x.x.x) — 提取 IPv4 部分检查
        if (isIPv4Mapped(b)) {
            return isBlockedV4(new byte[]{b[12], b[13], b[14], b[15]});
        }
        // fc00::/7 唯一本地地址
        int first = b[0] & 0xFF;
        if (first == 0xFC || first == 0xFD) return true;
        // fe80::/10 链路本地
        if (first == 0xFE && (b[1] & 0xC0) == 0x80) return true;
        return false;
    }

    private static boolean isIPv6Loopback(byte[] b) {
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) return false;
        }
        return b[15] == 1;
    }

    private static boolean isAllZeros(byte[] b) {
        for (byte x : b) {
            if (x != 0) return false;
        }
        return true;
    }

    private static boolean isIPv4Mapped(byte[] b) {
        // ::ffff:x.x.x.x = 10 bytes of 0, then 0xFF 0xFF, then 4 bytes of IPv4
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }

    /**
     * SSRF 安全异常。
     */
    public static class SsrfException extends RuntimeException {
        public SsrfException(String message) {
            super(message);
        }
    }

    /**
     * 创建 SSRF 安全的 RestTemplate。
     * 自定义 DnsResolver + 禁用重定向 + 超时。
     */
    public static RestTemplate createSsrfSafeRestTemplate() {
        // 1. 自定义 DnsResolver：解析后即刻检查 + 绑定 IP
        DnsResolver ssrfSafeDns = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress addr : addresses) {
                    if (isBlockedAddress(addr)) {
                        throw new UnknownHostException(
                                "HTTP hook blocked: " + host + " resolves to " + addr.getHostAddress());
                    }
                }
                return addresses;
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                return host;
            }
        };

        // 2. 构建 HttpClient：禁用重定向 + 绑定 DnsResolver
        var connMgr = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(ssrfSafeDns)
                .build();
        var httpClient = HttpClients.custom()
                .setConnectionManager(connMgr)
                .disableRedirectHandling() // 原版 maxRedirects=0
                .build();

        // 3. 设置超时
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(Duration.ofSeconds(30));
        // Note: read timeout is set at request level via RequestConfig in HttpClient5

        return new RestTemplate(factory);
    }
}
