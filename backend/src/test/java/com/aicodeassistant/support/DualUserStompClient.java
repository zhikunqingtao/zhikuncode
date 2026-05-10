package com.aicodeassistant.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 双用户 STOMP 客户端 — 用于 CoordinatorEventBus 的用户隔离断言。
 *
 * <p>对应 Task3-5 方案 §11.11 资产 #12。用例示意：
 * <pre>{@code
 *   DualUserStompClient.Connection a = client.connect("userA", "8080");
 *   DualUserStompClient.Connection b = client.connect("userB", "8080");
 *   a.subscribe("/user/queue/coordinator/sess-1");
 *   b.subscribe("/user/queue/coordinator/sess-1");
 *   // trigger event via HTTP ...
 *   Map<String,Object> envA = a.pollFrame(2, TimeUnit.SECONDS);
 *   assertThat(b.pollFrame(1, TimeUnit.SECONDS)).isNull(); // 隔离
 * }</pre>
 *
 * <p>仅测试用；在 spring-boot-starter-test 中已包含依赖。
 */
public class DualUserStompClient {

    private final WebSocketStompClient client;

    public DualUserStompClient() {
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        this.client.setMessageConverter(new MappingJackson2MessageConverter(new ObjectMapper()));
    }

    public Connection connect(String principal, int port)
            throws ExecutionException, InterruptedException, TimeoutException {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("X-Test-Principal", principal);
        String url = String.format("ws://localhost:%d/ws", port);
        StompSession session = client.connectAsync(url, headers, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        return new Connection(session, principal);
    }

    public void shutdown() {
        client.stop();
    }

    /** 单个 principal 的 STOMP 连接封装，消息异步塞入 BlockingQueue。 */
    public static class Connection {
        private final StompSession session;
        private final String principal;
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

        private Connection(StompSession session, String principal) {
            this.session = session;
            this.principal = principal;
        }

        public String principal() { return principal; }

        public StompSession.Subscription subscribe(String destination) {
            return session.subscribe(destination, new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return Object.class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) {
                    if (payload != null) queue.add(payload);
                }
            });
        }

        public Object pollFrame(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        public int pendingFrames() {
            return queue.size();
        }

        public void disconnect() {
            session.disconnect();
        }
    }
}
