package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.uitils.AtCommandHelper;
import app.simsmartgsm.uitils.PortWorker;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class GsmListenerService {

    private final RemoteStompClientConfig remoteStompClientConfig;

    /** Map quản lý worker cho từng SIM (COM port) */
    private final Map<String, PortWorker> workers = new ConcurrentHashMap<>();

    /** Map quản lý session thuê SIM */
    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();

    private final boolean testMode = true;

    // === Thuê SIM ===
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country) {
        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes, country);
        activeSessions.computeIfAbsent(sim.getId(), k -> new ArrayList<>()).add(session);

        log.info("➕ Rent SIM {} by acc={} services={} duration={}m",
                sim.getPhoneNumber(), accountId, services, durationMinutes);

        // Start worker nếu chưa có
        startWorkerForSim(sim);

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

    // === Tạo worker cho SIM ===
    private void startWorkerForSim(Sim sim) {
        workers.computeIfAbsent(sim.getComName(), com -> {
            PortWorker worker = new PortWorker(sim.getComName(), 7000, this);
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

    // === Xử lý SMS nhận về từ PortWorker ===
    protected void processSms(Sim sim, AtCommandHelper.SmsRecord rec) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        if (sessions.isEmpty()) return;

        String smsNorm = normalize(rec.body);
        String otp = extractOtp(rec.body);
        if (otp == null) return;

        // lấy prefix 4 ký tự đầu tiên từ SMS
        String prefix = smsNorm.length() >= 4 ? smsNorm.substring(0, 4) : smsNorm;
        log.info("🔎 SMS prefix = {}", prefix);

        boolean matched = false;

        for (RentSession s : sessions) {
            if (!s.isActive()) continue;

            for (String service : s.getServices()) {
                String serviceNorm = normalize(service);
                String servicePrefix = serviceNorm.substring(0, Math.min(4, serviceNorm.length()));

                log.info("🔎 Compare prefix={} with servicePrefix={}", prefix, servicePrefix);

                if (prefix.equals(servicePrefix)) {
                    log.info("✅ Matched service={} for acc={}", service, s.getAccountId());
                    forwardToSocket(sim, s, service, rec, otp);
                    matched = true;
                }
            }
        }

        if (!matched) {
            RentSession first = sessions.stream().filter(RentSession::isActive).findFirst().orElse(null);
            if (first != null) {
                String service = first.getServices().isEmpty() ? "UNKNOWN" : first.getServices().get(0);
                log.info("↪️ Fallback forward with service={}", service);
                forwardToSocket(sim, first, service, rec, otp);
            }
        }

        sessions.removeIf(s -> !s.isActive());
    }


    // === Forward OTP lên broker ===
    private void forwardToSocket(Sim sim, RentSession s, String service, AtCommandHelper.SmsRecord rec, String otp) {
        Map<String, Object> wsMessage = new HashMap<>();
        wsMessage.put("deviceName", sim.getDeviceName());
        wsMessage.put("phoneNumber", sim.getPhoneNumber());
        wsMessage.put("comNumber", sim.getComName());
        wsMessage.put("customerId", s.getAccountId());
        wsMessage.put("serviceCode", service);
        wsMessage.put("countryName", s.getCountry().getCountryCode());
        wsMessage.put("smsContent", rec.body);
        wsMessage.put("fromNumber", rec.sender);
        wsMessage.put("otp", otp);

        StompSession session = remoteStompClientConfig.getSession();
        if (session != null && session.isConnected()) {
            session.send("/topic/receive-otp", wsMessage);
            log.info("📤 Forward OTP [{}] for acc={} service={} -> remote", otp, s.getAccountId(), service);
        } else {
            log.warn("⚠️ Remote not connected, cannot forward OTP (service={}, otp={})", service, otp);
        }
    }

    // === Utils ===
    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", "");
    }

    private String extractOtp(String content) {
        Matcher m = Pattern.compile("\\b\\d{4,8}\\b").matcher(content);
        return m.find() ? m.group() : null;
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

        boolean isActive() {
            return Instant.now().isBefore(startTime.plus(Duration.ofMinutes(durationMinutes)));
        }
    }
}