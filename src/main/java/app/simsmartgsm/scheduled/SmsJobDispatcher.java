package app.simsmartgsm.scheduled;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.SmsMessageRepository;
import app.simsmartgsm.service.PortManager;
import app.simsmartgsm.uitils.AtCommandHelper;
import app.simsmartgsm.uitils.MarketingSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsJobDispatcher {

    private final RemoteStompClientConfig stompClientConfig;
    private final SmsMessageRepository smsMessageRepository;
    private final PortManager portManager;
    private final MarketingSessionRegistry marketingRegistry;

    private String localDeviceName;
    private final Map<String, BlockingQueue<JSONObject>> comQueues = new ConcurrentHashMap<>();
    private final Map<String, Thread> comWorkers = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            this.localDeviceName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            this.localDeviceName = "UNKNOWN_HOST";
            log.error("❌ Không lấy được hostname", e);
        }
        log.info("💻 Local deviceName: {}", localDeviceName);

        new Thread(this::subscribeSmsJobs, "SmsJobSubscriber").start();
    }

    private void subscribeSmsJobs() {
        while (true) {
            try {
                StompSession session = stompClientConfig.getSession();
                if (session != null && session.isConnected()) {
                    session.subscribe("/topic/sms-job-topic", new StompFrameHandler() {
                        @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                        @Override public void handleFrame(StompHeaders headers, Object payload) {
                            try {
                                String body = new String((byte[]) payload);
                                JSONObject job = new JSONObject(body);

                                // Lọc theo deviceName
                                if (!localDeviceName.equalsIgnoreCase(job.optString("deviceName"))) {
                                    log.debug("🚫 Bỏ qua job không khớp deviceName");
                                    return;
                                }

                                String comName = job.getString("comName");
                                comQueues
                                        .computeIfAbsent(comName, k -> {
                                            BlockingQueue<JSONObject> q = new LinkedBlockingQueue<>();
                                            startComWorker(comName, q, session);
                                            return q;
                                        })
                                        .offer(job);

                            } catch (Exception e) {
                                log.error("❌ Lỗi khi parse job", e);
                            }
                        }
                    });
                    log.info("✅ Subscribed to /topic/sms-job-topic");
                    break;
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("❌ Lỗi subscribe SMS job", e);
            }
        }
    }

    /** Worker: mỗi COM có 1 thread riêng, serialize job theo cổng */
    private void startComWorker(String comName, BlockingQueue<JSONObject> queue, StompSession session) {
        Thread worker = new Thread(() -> {
            while (true) {
                try {
                    JSONObject job = queue.take();
                    processSmsJob(job, comName, session);
                } catch (Exception e) {
                    log.error("❌ Lỗi worker COM {}", comName, e);
                }
            }
        }, "Worker-" + comName);
        worker.start();
        comWorkers.put(comName, worker);
        log.info("✅ Started worker for COM {}", comName);
    }

    /** Xử lý 1 job gửi SMS (ONE_WAY | TWO_WAY) + retry 3 lần */
    private void processSmsJob(JSONObject job, String comName, StompSession session) {
        // common fields
        String localMsgId = job.optString("localMsgId", "");
        String simId      = job.optString("simId", "");
        String simPhone   = job.optString("simPhoneNumber", "");
        String toNumber   = job.optString("phoneNumber", "");
        String content    = job.optString("content", "");
        String campaignId = job.optString("campaignId", null);
        String sessionId  = job.optString("sessionId", null);

        // marketing mode
        String mode = job.optString("smsType", "ONE_WAY");            // ONE_WAY | TWO_WAY
        int replyWindowMinutes = job.optInt("timeDuration", 0);

        JSONObject response = new JSONObject();
        response.put("localMsgId", localMsgId);
        response.put("simId",      simId);
        response.put("campaignId", campaignId);
        response.put("sessionId",  sessionId);
        response.put("phoneNumber", toNumber);

        boolean success = false;
        String errorMsg = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                log.info("📤 Gửi SMS attempt {} qua {} (SIM={}) → {}: {}", attempt, comName, simPhone, toNumber, content);
                success = sendSmsViaCom(comName, toNumber, content);
                if (success) break;
                Thread.sleep(1200);
            } catch (Exception e) {
                errorMsg = e.getMessage();
                log.warn("⚠️ Attempt {} fail COM {}: {}", attempt, comName, errorMsg);
                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
            }
        }

        if (success) {
            response.put("status", "SENT");
            response.put("errorMsg", JSONObject.NULL);

            // Nếu TWO_WAY → đăng ký phiên chờ phản hồi
            if ("TWO_WAY".equalsIgnoreCase(mode) && replyWindowMinutes > 0) {
                Instant expiresAt = Instant.now().plus(replyWindowMinutes, ChronoUnit.MINUTES);
                marketingRegistry.register(simPhone, toNumber, campaignId, sessionId, expiresAt);
                log.info("🕒 Registered TWO_WAY session sim={} ↔ cus={} until {}", simPhone, toNumber, expiresAt);
            }
        } else {
            response.put("status", "FAILED");
            response.put("errorMsg", errorMsg != null ? errorMsg : "Unknown error");
            log.error("❌ Gửi SMS thất bại sau 3 lần COM {} → {}", comName, toNumber);
        }

        // Push WS kết quả gửi
        session.send("/app/sms-response", response.toString().getBytes());
        log.info("📩 Kết quả gửi WS: {}", response);

        // Lưu OUTBOX
        SmsMessage sms = new SmsMessage();
        sms.setOrderId(campaignId);
        sms.setDeviceName(localDeviceName);
        sms.setComPort(comName);
        sms.setSimPhone(simPhone);
        sms.setFromNumber(simPhone);
        sms.setToNumber(toNumber);
        sms.setContent(content);
        sms.setModemResponse(success ? "OK" : (errorMsg != null ? errorMsg : "ERROR"));
        sms.setType("OUTBOX");
        sms.setTimestamp(Instant.now());

        smsMessageRepository.save(sms);
        log.info("💾 Saved SMS OUTBOX to DB: {}", sms.getId());
    }

    /** Gửi SMS thực qua PortManager + AtCommandHelper */
    private boolean sendSmsViaCom(String comName, String phoneNumber, String content) {
        Boolean ok = portManager.withPort(comName, (AtCommandHelper helper) -> {
            try {
                // B1: CMGS
                String resp = helper.sendAndRead("AT+CMGS=\"" + phoneNumber + "\"", 2000);
                if (resp == null || !resp.contains(">")) {
                    log.error("❌ Không nhận prompt '>' từ modem {}", comName);
                    return false;
                }

                // B2: nội dung + Ctrl+Z
                helper.writeRaw(content.getBytes());
                helper.writeCtrlZ();

                // B3: chờ phản hồi cuối
                String finalResp = helper.sendAndRead("", 5000); // đọc tiếp
                log.info("📩 Resp từ {}: {}", comName, finalResp);
                return finalResp != null && finalResp.contains("OK");

            } catch (Exception e) {
                log.error("❌ Error send SMS via {}: {}", comName, e.getMessage(), e);
                return false;
            }
        }, 5000);

        return ok != null && ok;
    }
}
