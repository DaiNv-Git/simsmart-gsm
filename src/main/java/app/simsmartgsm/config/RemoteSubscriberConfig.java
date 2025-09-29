package app.simsmartgsm.config;

import app.simsmartgsm.dto.request.RentSimRequest;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.CountryRepository;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.service.GsmListenerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class RemoteSubscriberConfig {

    private static final String REMOTE_WS_URL = "ws://72.60.41.168:9090/ws";
    private static final String SUB_TOPIC = "/topic/send-otp";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AtomicReference<StompSession> stompSessionRef = new AtomicReference<>();

    private final GsmListenerService gsmListenerService;
    private final SimRepository simRepository;
    private final CountryRepository countryRepository;

    @PostConstruct
    public void subscribeToRemoteBroker() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.afterPropertiesSet();

        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);

        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        stompClient.setTaskScheduler(taskScheduler);
        stompClient.setDefaultHeartbeat(new long[]{10000, 10000});

        log.info("🌐 Connecting to remote broker at {}", REMOTE_WS_URL);

        CompletableFuture<StompSession> futureSession =
                stompClient.connectAsync(REMOTE_WS_URL, new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        log.info("✅ Connected to {}", REMOTE_WS_URL);
                        stompSessionRef.set(session);
                        subscribeToTopic(session);
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        log.error("❌ Transport error: {}", exception.getMessage(), exception);
                        stompSessionRef.set(null);
                        retryConnect();
                    }
                });

        futureSession.exceptionally(ex -> {
            log.error("❌ Failed to connect to {}: {}", REMOTE_WS_URL, ex.getMessage());
            retryConnect();
            return null;
        });
    }

    private void subscribeToTopic(StompSession session) {
        session.subscribe(SUB_TOPIC, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Object.class; // nhận payload raw
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    String json;
                    if (payload instanceof byte[]) {
                        json = new String((byte[]) payload, StandardCharsets.UTF_8);
                    } else {
                        json = payload.toString();
                    }

                    log.info("📩 Raw JSON from broker: {}", json);

                    RentSimRequest req = mapper.readValue(json, RentSimRequest.class);
                    log.info("✅ Parsed RentSimRequest: {}", req);

                    // 🔎 Lấy tên host hiện tại
                    String localHostName = java.net.InetAddress.getLocalHost().getHostName();

                    // ✅ Chỉ xử lý nếu deviceName khớp với host
                    if (!localHostName.equalsIgnoreCase(req.getDeviceName())) {
                        log.info("⏭️ Bỏ qua request vì deviceName={} không khớp với host={}",
                                req.getDeviceName(), localHostName);
                        return;
                    }

                    Sim sim = simRepository.findByPhoneNumber(req.getPhoneNumber())
                            .orElseThrow(() -> new RuntimeException("SIM not found: " + req.getPhoneNumber()));

                    Country country = countryRepository.findByCountryCode(req.getCountryCode())
                            .orElseThrow(() -> new RuntimeException("Country not found: " + req.getCountryCode()));

                    gsmListenerService.rentSim(
                            sim,
                            req.getCustomerId(),
                            req.getServiceCodeList(),
                            req.getRentDuration(),
                            country,req.getOrderId(),
                            req.getType()
                    );

                } catch (Exception e) {
                    log.error("❌ Error parsing payload: {}", e.getMessage(), e);
                }
            }
        });
        log.info("👂 Subscribed to {}", SUB_TOPIC);
    }

    private void retryConnect() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                log.info("🔄 Retrying connection to {}", REMOTE_WS_URL);
                subscribeToRemoteBroker();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    public StompSession getSession() {
        return stompSessionRef.get();
    }
}
