package app.simsmartgsm.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
@Slf4j
public class RemoteStompClientConfig {

    private static final String REMOTE_WS_URL = "ws://72.60.41.168/ws-endpoint";
    private final AtomicReference<StompSession> stompSessionRef = new AtomicReference<>();

    @PostConstruct
    public void connectToRemoteBroker() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.afterPropertiesSet();

        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);

        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        stompClient.setTaskScheduler(taskScheduler);
        stompClient.setDefaultHeartbeat(new long[]{10000, 10000});

        log.info("🌐 Connecting to remote WS broker: {}", REMOTE_WS_URL);

        // ✅ Spring 6: dùng connectAsync (không deprecated)
        CompletableFuture<StompSession> futureSession =
                stompClient.connectAsync(REMOTE_WS_URL, new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        log.info("✅ Connected to {}", REMOTE_WS_URL);
                        stompSessionRef.set(session);
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        log.error("❌ Transport error: {}", exception.getMessage(), exception);
                        stompSessionRef.set(null);
                        retryConnect();
                    }
                });

        // có thể xử lý thêm nếu cần
        futureSession.exceptionally(ex -> {
            log.error("❌ Failed to connect to {}: {}", REMOTE_WS_URL, ex.getMessage());
            retryConnect();
            return null;
        });
    }

    private void retryConnect() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                log.info("🔄 Reconnecting to {}", REMOTE_WS_URL);
                connectToRemoteBroker();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    public StompSession getSession() {
        return stompSessionRef.get();
    }
}
