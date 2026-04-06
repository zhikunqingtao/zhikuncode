package com.aicodeassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
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

import java.security.Principal;
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
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    /**
     * 配置入站通道拦截器 — CONNECT 帧认证 + SessionId 提取。
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor());
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

    // ───── STOMP 认证拦截器 ─────

    /**
     * STOMP CONNECT 帧拦截器 — 提取 Authorization + X-Session-Id。
     * <p>
     * P0 阶段: localhost 模式不校验 JWT，为每个连接生成唯一 Principal。
     * P1 阶段: 集成 JwtService 进行 Bearer Token 校验。
     */
    private static class StompAuthInterceptor implements ChannelInterceptor {

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor
                    .getAccessor(message, StompHeaderAccessor.class);

            if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                // 提取 Authorization header (P0: 仅记录, 不做 JWT 验证)
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                String principalName;

                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    // P1: 此处应调用 jwtService.authenticate(token)
                    // P0: 使用 token 尾部作为标识
                    String token = authHeader.substring(7);
                    principalName = "user-" + token.substring(Math.max(0, token.length() - 8));
                    log.debug("STOMP CONNECT with Bearer token, principal={}", principalName);
                } else {
                    // P0 localhost 模式: 生成唯一标识
                    principalName = "anon-" + UUID.randomUUID().toString().substring(0, 8);
                    log.debug("STOMP CONNECT without auth, principal={}", principalName);
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
            }

            return message;
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
}
