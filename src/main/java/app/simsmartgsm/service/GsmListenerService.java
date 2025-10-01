package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.entity.SmsMessage;
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
    private final ServiceRepository serviceRepository;
    private final Map<String, PortWorker> workers = new ConcurrentHashMap<>();
    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();

    private final boolean testMode = true;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @Value("${gsm.order-api.base-url}")
    private String orderApiBaseUrl;

    // === Thuê SIM ===
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country, String orderId, String type) {
        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes,
                country, orderId, OtpSessionType.fromString(type),false);
        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);

        log.info("➕ Rent SIM {} by acc={} services={} duration={}m",
                sim.getPhoneNumber(), accountId, services, durationMinutes);

        startWorkerForSim(sim);

        scheduler.schedule(() -> checkAndRefund(sim, session), durationMinutes, TimeUnit.MINUTES);

        // --- TEST MODE ---
        if (testMode && !services.isEmpty()) {
            String service = services.get(0);
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    String otp = generateOtp();
                    String fakeSms = service.toUpperCase() + " OTP " + otp;

                    AtCommandHelper.SmsRecord rec = new AtCommandHelper.SmsRecord();
                    rec.sender = "TEST-SENDER";
                    rec.body = fakeSms;

                    log.info("📩 [TEST MODE] Fake incoming SMS: {}", rec.body);
                    processSms(sim, rec);
                } catch (Exception e) {
                    log.error("❌ Error in test SMS thread: {}", e.getMessage(), e);
                }
            }).start();
        }
    }

    private void checkAndRefund(Sim sim, RentSession session) {
        if (session.isActive()) return;

        boolean hasOtp = smsMessageRepository.existsByOrderId(session.getOrderId());
        if (!hasOtp) {
            try {
                callUpdateRefundApi(session.getOrderId());
                log.info("🔄 Auto refund orderId={} (SIM={}, acc={}) vì hết hạn không nhận được OTP",
                        session.getOrderId(), sim.getPhoneNumber(), session.getAccountId());
            } catch (Exception e) {
                log.error("❌ Error calling refund API for orderId={}", session.getOrderId(), e);
            }
        } else {
            log.info("✅ Order {} đã có OTP, không cần refund", session.getOrderId());
        }

        // ✅ Sau khi xử lý xong, check có còn session active không
        stopWorkerIfNoActiveSession(sim);
    }


    // === Worker cho SIM ===
    private void startWorkerForSim(Sim sim) {
        workers.computeIfAbsent(sim.getComName(), com -> {
            PortWorker worker = new PortWorker(sim, 4000, this);
            new Thread(worker, "PortWorker-" + com).start();
            return worker;
        });
    }

    // === Gửi SMS ===
    public void sendSms(Sim sim, String to, String content) {
        PortWorker w = workers.get(sim.getComName());
        if (w != null) {
            w.sendSms(to, content);
        } else {
            log.warn("⚠️ No worker for sim {}", sim.getComName());
        }
    }

    // === Xử lý SMS nhận về ===
    public void processSms(Sim sim, AtCommandHelper.SmsRecord rec) {
        List<RentSession> sessions = new ArrayList<>(activeSessions.getOrDefault(sim.getId(), List.of()));
        if (sessions.isEmpty()) return;

        String smsNorm = normalize(rec.body);
        String otp = extractOtp(rec.body);
        if (otp == null) return;

        boolean matched = false;

        for (RentSession s : sessions) {
            if (!s.isActive()) continue;
            for (String service : s.getServices()) {
                String serviceNorm = normalize(service);
                String servicePrefix = serviceNorm.substring(0, Math.min(4, serviceNorm.length()));
                if (smsNorm.startsWith(servicePrefix)) {
                    handleOtpReceived(sim, s, service, rec, otp);
                    matched = true;
                }
            }
        }

        if (!matched) {
            RentSession first = sessions.stream().filter(RentSession::isActive).findFirst().orElse(null);
            if (first != null) {
                handleOtpReceived(sim, first, first.getServices().isEmpty() ? "UNKNOWN" : first.getServices().get(0), rec, otp);
            }
        }
    }

    // === Xử lý khi nhận OTP ===
    private void handleOtpReceived(Sim sim, RentSession s, String service, AtCommandHelper.SmsRecord rec, String otp) {
        if (s.isOtpReceived()) {
            log.info("⚠️ Order {} đã được cập nhật SUCCESS trước đó, bỏ qua OTP mới", s.getOrderId());
            return;
        }

        String resolvedServiceCode = serviceRepository.findByCode(service)
                .map(svc -> svc.getCode())
                .orElse(service);

        SmsMessage sms = SmsMessage.builder()
                .orderId(s.getOrderId())
                .accountId(s.getAccountId())
                .durationMinutes(s.getDurationMinutes())
                .deviceName(sim.getDeviceName())
                .comPort(sim.getComName())
                .simPhone(sim.getPhoneNumber())
                .serviceCode(resolvedServiceCode)
                .fromNumber(rec.sender)
                .toNumber(sim.getPhoneNumber())
                .content(rec.body)
                .modemResponse("OK")
                .type("INBOX")
                .timestamp(Instant.now())
                .build();

        smsMessageRepository.save(sms);

        log.info("💾 Saved SMS to DB orderId={} simPhone={} otp={} duration={}m",
                sms.getOrderId(), sms.getSimPhone(), otp, sms.getDurationMinutes());

        // ✅ Call API update success duy nhất 1 lần
        try {
            callUpdateSuccessApi(s.getOrderId());
            s.setOtpReceived(true); // đánh dấu đã xử lý OTP
        } catch (Exception e) {
            log.error("❌ Error calling update success API for orderId={}", s.getOrderId(), e);
        }

        // Forward OTP lên remote socket
        Map<String, Object> wsMessage = new HashMap<>();
        wsMessage.put("deviceName", sim.getDeviceName());
        wsMessage.put("phoneNumber", sim.getPhoneNumber());
        wsMessage.put("comNumber", sim.getComName());
        wsMessage.put("customerId", s.getAccountId());
        wsMessage.put("serviceCode", resolvedServiceCode);
        wsMessage.put("countryName", s.getCountry().getCountryCode());
        wsMessage.put("smsContent", rec.body);
        wsMessage.put("fromNumber", rec.sender);
        wsMessage.put("otp", otp);

        StompSession stompSession = remoteStompClientConfig.getSession();
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.send("/topic/receive-otp", wsMessage);
            log.info("📤 Forward OTP [{}] for acc={} service={} -> remote", otp, s.getAccountId(), service);
        } else {
            log.warn("⚠️ Remote not connected, cannot forward OTP (service={}, otp={})", service, otp);
        }
    }
    // === Call API update success/refund ===
    private void callUpdateSuccessApi(String orderId) {
        // Ghép path đúng với API thực tế
        String url = orderApiBaseUrl + "api/otp/order/" + orderId + "/success";
        restTemplate.postForEntity(url, null, Void.class);
    }

    private void callUpdateRefundApi(String orderId) {
        String url = orderApiBaseUrl + "api/otp/order/" + orderId + "/refund";
        restTemplate.postForEntity(url, null, Void.class);
    }

    // === Utils ===
    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", "");
    }

    private String extractOtp(String content) {
        Matcher m = Pattern.compile("\\b\\d{4,8}\\b").matcher(content);
        return m.find() ? m.group() : null;
    }
    private void stopWorkerIfNoActiveSession(Sim sim) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        boolean hasActive = sessions.stream().anyMatch(RentSession::isActive);
        if (!hasActive) {
            PortWorker w = workers.remove(sim.getComName());
            if (w != null) {
                w.stop(); // bên PortWorker nhớ implement stop()
                log.info("🛑 Stop worker for SIM={} vì không còn session active", sim.getPhoneNumber());
            }
        }
    }


    private String generateOtp() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100000, 999999));
    }


    // === RentSession ===
    @Data
    @AllArgsConstructor
    static class RentSession {
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