package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.entity.CallMessage;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.CallMessageRepository;
import app.simsmartgsm.repository.ServiceRepository;
import app.simsmartgsm.repository.SmsMessageRepository;
import app.simsmartgsm.uitils.AtCommandHelper;
import app.simsmartgsm.uitils.OtpSessionType;
import app.simsmartgsm.uitils.PortWorker;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class GsmListenerService {

    private final RemoteStompClientConfig remoteStompClientConfig;
    private final SmsMessageRepository smsMessageRepository;
    private final CallMessageRepository callMessageRepository;
    private final ServiceRepository serviceRepository;

    private final Map<String, PortWorker> workers = new ConcurrentHashMap<>();
    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();

    private final boolean testMode = true;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @Value("${gsm.order-api.base-url}")
    private String orderApiBaseUrl;

    // --- Thuê SIM ---
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country, String orderId, String type) {
        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes,
                country, orderId, OtpSessionType.fromString(type), false);
        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);

        startWorkerForSim(sim);

        // test mode
        if (testMode && session.getType() == OtpSessionType.SMS) {
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    AtCommandHelper.SmsRecord rec = new AtCommandHelper.SmsRecord();
                    rec.sender = "TEST";
                    rec.body = "FACEBOOK OTP 123456";
                    processSms(sim, rec);
                } catch (Exception e) { log.error("❌ Test SMS error", e); }
            }).start();
        }

        if (testMode && session.getType() == OtpSessionType.CALL) {
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    processIncomingCall(sim, "0901234567", session);
                } catch (Exception e) { log.error("❌ Test CALL error", e); }
            }).start();
        }
    }

    // --- Worker ---
    private void startWorkerForSim(Sim sim) {
        workers.computeIfAbsent(sim.getComName(), com -> {
            PortWorker worker = new PortWorker(sim, 4000, this);
            new Thread(worker, "PortWorker-" + com).start();
            return worker;
        });
    }

    // --- Xử lý SMS ---
    public void processSms(Sim sim, AtCommandHelper.SmsRecord rec) {
        // filter session type SMS
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        for (RentSession s : sessions) {
            if (!s.isActive() || s.getType() != OtpSessionType.SMS) continue;
            String otp = extractOtp(rec.body);
            if (otp != null) {
                handleOtpReceived(sim, s, rec, otp);
            }
        }
    }

    private void handleOtpReceived(Sim sim, RentSession s, AtCommandHelper.SmsRecord rec, String otp) {
        SmsMessage sms = SmsMessage.builder()
                .orderId(s.getOrderId())
                .accountId(s.getAccountId())
                .simPhone(sim.getPhoneNumber())
                .fromNumber(rec.sender)
                .toNumber(sim.getPhoneNumber())
                .content(rec.body)
                .timestamp(Instant.now())
                .type("INBOX")
                .build();
        smsMessageRepository.save(sms);

        // notify socket
        Map<String, Object> wsMessage = new HashMap<>();
        wsMessage.put("orderId", s.getOrderId());
        wsMessage.put("otp", otp);
        StompSession stompSession = remoteStompClientConfig.getSession();
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.send("/topic/receive-otp", wsMessage);
        }
    }

    // --- Xử lý CALL ---
    public void processIncomingCall(Sim sim, String fromNumber, RentSession session) {
        CallMessage call = CallMessage.builder()
                .orderId(session.getOrderId())
                .accountId(session.getAccountId())
                .simPhone(sim.getPhoneNumber())
                .fromNumber(fromNumber)
                .toNumber(sim.getPhoneNumber())
                .startTime(Instant.now())
                .endTime(Instant.now().plusSeconds(20))
                .status("RECEIVED")
                .recordingPath(recordCall(sim.getComName()))
                .build();
        callMessageRepository.save(call);

        // notify socket
        Map<String, Object> wsMessage = new HashMap<>();
        wsMessage.put("orderId", session.getOrderId());
        wsMessage.put("fromNumber", fromNumber);
        wsMessage.put("recordingPath", call.getRecordingPath());
        StompSession stompSession = remoteStompClientConfig.getSession();
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.send("/topic/received_call", wsMessage);
        }
    }

    private String recordCall(String comPort) {
        return "/tmp/call_" + comPort + "_" + System.currentTimeMillis() + ".wav";
    }

    // --- Utils ---
    private String extractOtp(String content) {
        Matcher m = Pattern.compile("\\b\\d{4,8}\\b").matcher(content);
        return m.find() ? m.group() : null;
    }

    // --- RentSession ---
    @Data
    @AllArgsConstructor
    public static class RentSession {
        private Long accountId;
        private List<String> services;
        private Instant startTime;
        private int durationMinutes;
        private Country country;
        private String orderId;
        private OtpSessionType type;
        private boolean otpReceived;

        boolean isActive() {
            return Instant.now().isBefore(startTime.plus(Duration.ofMinutes(durationMinutes)));
        }
    }
}
