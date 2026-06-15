package com.aicodeassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.Message;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket STOMP 配置 (§6.2, §8.5.4)。
 * <p>
 * 功能:
 * <ul>
 *   <li>STOMP over SockJS (自动协商 WebSocket → xhr-streaming → xhr-polling)</li>
 *   <li>Simple Broker: /topic (广播) + /queue (用户专属)</li>
 *   <li>STOMP 心跳: 10s incoming + 10s outgoing</li>
 *   <li>CONNECT 帧拦截: JWT 认证 + X-Session-Id 提取</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final RemoteAccessSecurityFilter securityFilter;

    public WebSocketConfig(RemoteAccessSecurityFilter securityFilter) {
        this.securityFilter = securityFilter;
    }

    /**
     * 配置底层 Servlet 容器的 WebSocket 缓冲区大小。
     * SockJS 使用 WebSocket 传输时，Tomcat 默认 maxTextMessageBufferSize=8KB，
     * 无法承载 base64 图片数据（1-5MB），必须在容器级别提升。
     */
    @Bean
    @Profile("!test")
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(10 * 1024 * 1024);   // 10MB
        container.setMaxBinaryMessageBufferSize(10 * 1024 * 1024); // 10MB
        return container;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 客户端订阅前缀: /topic (广播) + /queue (用户专属)
        config.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10000, 10000}) // STOMP 传输层心跳 10s
                .setTaskScheduler(heartbeatScheduler());
        // 客户端发送前缀
        config.setApplicationDestinationPrefixes("/app");
        // 用户目标前缀
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // v1.44.0: 固定端点 /ws，sessionId 通过 CONNECT 帧 header 传递
        List<String> origins = new ArrayList<>(List.of(
            "http://localhost:5173", "http://localhost:8080",
            "http://127.0.0.1:5173", "http://127.0.0.1:8080"));
        // ALLOW_PRIVATE_NETWORK=true 时，自动添加本机局域网 IP（支持手机/平板访问）
        if (Boolean.parseBoolean(System.getenv().getOrDefault("ALLOW_PRIVATE_NETWORK", "false"))) {
            for (String localIp : getLocalIps()) {
                origins.add("http://" + localIp + ":5173");
                origins.add("http://" + localIp + ":8080");
            }
        }
        registry.addEndpoint("/ws")
                .setAllowedOrigins(origins.toArray(String[]::new))
                .withSockJS();
    }

    /**
     * WebSocket 传输层配置 — 确保 inbound channel 有充足容量。
     */
    @Override
    public void configureWebSocketTransport(
            org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(10 * 1024 * 1024);   // 10MB 消息大小（支持图片base64传输）
        registration.setSendBufferSizeLimit(10 * 1024 * 1024); // 10MB 发送缓冲
        registration.setSendTimeLimit(30 * 1000);         // 30s 发送超时
    }

    /**
     * 配置入站通道拦截器 — CONNECT 帧认证 + SessionId 提取。
     * 增加线程池容量，防止 "Failed to send message to clientInboundChannel" 错误。
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
            new DiagnosticChannelInterceptor(),  // 诊断拦截器（先于认证）
            new StompAuthInterceptor(securityFilter)
        );
        registration.taskExecutor()
                .corePoolSize(4)
                .maxPoolSize(20)
                .queueCapacity(200);
    }

    /**
     * STOMP 心跳调度器 — SimpleBroker 需要 TaskScheduler 来发送心跳帧。
     */
    @Bean
    public TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    // ───── 诊断拦截器 — 记录所有入站 STOMP 帧 ─────

    /**
     * 临时诊断拦截器 — 记录每个通过 clientInboundChannel 的消息。
     */
    private static class DiagnosticChannelInterceptor implements ChannelInterceptor {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor
                    .getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null) {
                if (accessor.getCommand() != null) {
                    log.info("[DIAG] clientInbound: cmd={}, dest={}, user={}, sessionId={}",
                        accessor.getCommand(),
                        accessor.getDestination(),
                        accessor.getUser() != null ? accessor.getUser().getName() : "null",
                        accessor.getSessionId());
                } else {
                    log.trace("[DIAG] clientInbound: heartbeat, sessionId={}", accessor.getSessionId());
                }
            }
            return message;
        }
    }

    // ───── STOMP 认证拦截器 ─────

    /**
     * STOMP CONNECT 帧拦截器 — Bearer Token 认证 + X-Session-Id 提取。
     * <p>
     * 验证逻辑: Bearer token 与 RemoteAccessSecurityFilter 生成的 accessToken 匹配。
     * localhost 连接若无 token 则生成匿名 Principal（保持本地开发兼容）。
     */
    private static class StompAuthInterceptor implements ChannelInterceptor {

        private final RemoteAccessSecurityFilter securityFilter;

        StompAuthInterceptor(RemoteAccessSecurityFilter securityFilter) {
            this.securityFilter = securityFilter;
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            try {
                StompHeaderAccessor accessor = MessageHeaderAccessor
                        .getAccessor(message, StompHeaderAccessor.class);

                if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                    return message;  // 非CONNECT帧直接放行
                }
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                String principalName;

                if (authHeader != null && authHeader.startsWith("Bearer ")
                        && authHeader.length() > 7) {
                    String token = authHeader.substring(7);
                    // 验证 token 与 accessToken 匹配
                    if (!securityFilter.getAccessToken().equals(token)) {
                        log.warn("STOMP CONNECT rejected: invalid Bearer token");
                        throw new org.springframework.security.access.AccessDeniedException(
                                "Invalid access token");
                    }
                    principalName = "user-" + token.substring(Math.max(0, token.length() - 8));
                    log.debug("STOMP CONNECT authenticated, principal={}", principalName);
                } else {
                    // localhost 模式: 无 token 时生成匿名 Principal（本地开发兼容）
                    principalName = "anon-" + UUID.randomUUID().toString().substring(0, 8);
                    log.debug("STOMP CONNECT without auth (localhost), principal={}", principalName);
                }

                // 设置 Principal
                accessor.setUser(new StompPrincipal(principalName));

                // v1.44.0: 从 CONNECT header 提取 sessionId
                String sessionId = accessor.getFirstNativeHeader("X-Session-Id");
                if (sessionId != null) {
                    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                    if (sessionAttributes == null) {
                        sessionAttributes = new ConcurrentHashMap<>();
                        accessor.setSessionAttributes(sessionAttributes);
                    }
                    sessionAttributes.put("sessionId", sessionId);
                    log.debug("STOMP CONNECT sessionId={}", sessionId);
                }

                return message;
            } catch (org.springframework.security.access.AccessDeniedException e) {
                throw e;  // 认证失败必须报错
            } catch (Exception e) {
                log.error("StompAuthInterceptor error: {}", e.getMessage(), e);
                return message;  // 其他异常不阻断消息流
            }
        }
    }

    /**
     * 简单 Principal 实现 — STOMP 连接标识。
     */
    public record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    /**
     * 获取本机所有局域网 IP 地址。
     */
    private static List<String> getLocalIps() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isSiteLocalAddress() && !addr.getHostAddress().contains(":")) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return ips;
    }
}
