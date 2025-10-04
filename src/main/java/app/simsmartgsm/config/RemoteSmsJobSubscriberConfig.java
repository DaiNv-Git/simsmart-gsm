package app.simsmartgsm.config;

import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.SmsMessageRepository;
import app.simsmartgsm.service.PortManager;
import app.simsmartgsm.uitils.AtCommandHelper;
import app.simsmartgsm.uitils.MarketingSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
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
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class RemoteSmsJobSubscriberConfig {

    private static final String REMOTE_WS_URL = "http://72.60.41.168:9090/ws"; // SockJS endpoint
    private static final String SUB_TOPIC = "/topic/sms-job-topic";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SmsMessageRepository smsMessageRepository;
    private final PortManager portManager;
    private final MarketingSessionRegistry marketingRegistry;

    private String localDeviceName;
    private final AtomicReference<StompSession> stompSessionRef = new AtomicReference<>();

    @PostConstruct
    public void subscribeToRemoteBroker() {
        try {
            this.localDeviceName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            this.localDeviceName = "UNKNOWN_HOST";
        }
        log.info("💻 Local deviceName: {}", localDeviceName);

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.afterPropertiesSet();

        // ✅ Spring 6+: dùng RestTemplateXhrTransport thay vì XhrTransport
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

        log.info("🌐 Connecting to remote broker at {}", REMOTE_WS_URL);

        // ✅ Spring 6+: dùng connectAsync (không deprecated)
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
        }).exceptionally(ex -> {
            log.error("❌ Failed to connect: {}", ex.getMessage());
            retryConnect();
            return null;
        });
    }

    private void subscribeToTopic(StompSession session) {
        session.subscribe(SUB_TOPIC, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Object.class;
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
                    JSONObject job = new JSONObject(json);

                    // ✅ Filter theo deviceName
                    if (!localDeviceName.equalsIgnoreCase(job.optString("deviceName"))) {
                        log.info("⏭️ Bỏ qua job vì deviceName={} không khớp host={}",
                                job.optString("deviceName"), localDeviceName);
                        return;
                    }

                    String comName = job.optString("comName");
                    String toNumber = job.optString("phoneNumber");
                    String content = job.optString("content");
                    String simPhone = job.optString("simPhoneNumber");
                    String campaignId = job.optString("campaignId", null);

                    log.info("📤 Gửi SMS qua {} → {}: {}", comName, toNumber, content);
                    boolean success = sendSmsViaCom(comName, toNumber, content);

                    JSONObject result = new JSONObject();
                    result.put("deviceName", localDeviceName);
                    result.put("comName", comName);
                    result.put("phoneNumber", toNumber);
                    result.put("status", success ? "SENT" : "FAILED");

                    log.info("📬 Kết quả gửi SMS: {}", result);

                    SmsMessage sms = new SmsMessage();
                    sms.setOrderId(campaignId);
                    sms.setDeviceName(localDeviceName);
                    sms.setComPort(comName);
                    sms.setSimPhone(simPhone);
                    sms.setFromNumber(simPhone);
                    sms.setToNumber(toNumber);
                    sms.setContent(content);
                    sms.setModemResponse(success ? "OK" : "ERROR");
                    sms.setType("OUTBOX");
                    sms.setTimestamp(Instant.now());
                    smsMessageRepository.save(sms);
                    log.info("💾 Đã lưu OUTBOX: {}", sms.getId());

                } catch (Exception e) {
                    log.error("❌ Lỗi xử lý job: {}", e.getMessage(), e);
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

    /** Gửi SMS thực qua PortManager + AtCommandHelper */
    private boolean sendSmsViaCom(String comName, String phoneNumber, String content) {
        Boolean ok = portManager.withPort(comName, (AtCommandHelper helper) -> {
            try {
                String resp = helper.sendAndRead("AT+CMGS=\"" + phoneNumber + "\"", 2000);
                if (resp == null || !resp.contains(">")) {
                    log.error("❌ Không nhận prompt '>' từ modem {}", comName);
                    return false;
                }

                helper.writeRaw(content.getBytes());
                helper.writeCtrlZ();

                String finalResp = helper.sendAndRead("", 5000);
                log.info("📩 Phản hồi từ {}: {}", comName, finalResp);
                return finalResp != null && finalResp.contains("OK");

            } catch (Exception e) {
                log.error("❌ Lỗi gửi SMS qua {}: {}", comName, e.getMessage(), e);
                return false;
            }
        }, 5000);

        return ok != null && ok;
    }
}
