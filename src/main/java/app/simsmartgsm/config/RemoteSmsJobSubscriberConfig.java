package app.simsmartgsm.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@Slf4j
public class RemoteSmsJobSubscriberConfig {

    private static final String REMOTE_WS_URL = "http://72.60.41.168:9090/ws"; // ✅ SockJS endpoint
    private static final String SUB_TOPIC = "/topic/sms-job-topic";

    @PostConstruct
    public void connectToBroker() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.afterPropertiesSet();

        // ✅ Quan trọng: phải có RestTemplateXhrTransport để SockJS handshake thành công
        RestTemplate restTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        List<Transport> transports = List.of(
                new WebSocketTransport(new StandardWebSocketClient()),
                new RestTemplateXhrTransport(restTemplate)
        );

        SockJsClient sockJsClient = new SockJsClient(transports);
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        stompClient.setTaskScheduler(scheduler);
        stompClient.setDefaultHeartbeat(new long[]{10000, 10000});

        log.info("🌐 Connecting to {}", REMOTE_WS_URL);

        stompClient.connectAsync(REMOTE_WS_URL, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                log.info("✅ Connected to {}", REMOTE_WS_URL);
                subscribeToTopic(session);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                log.error("❌ Transport error: {}", exception.getMessage(), exception);
            }
        });
    }

    private void subscribeToTopic(StompSession session) {
        session.subscribe(SUB_TOPIC, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                String msg = new String((byte[]) payload, StandardCharsets.UTF_8);
                log.info("📩 Received from {}: {}", SUB_TOPIC, msg);
            }
        });
        log.info("👂 Subscribed to {}", SUB_TOPIC);
    }
}
