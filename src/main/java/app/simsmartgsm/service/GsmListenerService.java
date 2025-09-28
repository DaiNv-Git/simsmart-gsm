package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.uitils.AtCommandHelper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class GsmListenerService {

    private final RemoteStompClientConfig remoteStompClientConfig;
    private final PortManager portManager;

    // Lưu session đang thuê SIM
    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();
    private final Set<String> runningListeners = ConcurrentHashMap.newKeySet();

    // Bật/tắt test mode
    private final boolean testMode = true;

    // === Bắt đầu thuê SIM ===
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country) {
        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes, country);
        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);

        log.info("➕ Rent SIM {} by acc={} services={} duration={}m",
                sim.getPhoneNumber(), accountId, services, durationMinutes);

        if (!runningListeners.contains(sim.getId())) {
            startListener(sim);
        }

        // --- TEST MODE ---
        if (testMode && !services.isEmpty()) {
            String service = services.get(0);
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // delay giả lập
                    String otp = generateOtp();
                    String fakeSms = service.toUpperCase() + " OTP " + otp;

                    AtCommandHelper.SmsRecord rec = new AtCommandHelper.SmsRecord();
                    rec.sender = "TEST-SENDER";
                    rec.body = fakeSms;
                    rec.index = null;

                    log.info("📩 [TEST MODE] Fake incoming SMS: {}", rec.body);
                    processSms(sim, rec);
                } catch (Exception e) {
                    log.error("❌ Error in test SMS thread: {}", e.getMessage(), e);
                }
            }).start();
        }
    }

    // === Listener đọc SMS từ modem ===
    private void startListener(Sim sim) {
        if (!runningListeners.add(sim.getId())) return;

        new Thread(() -> {
            try {
                log.info("📡 Start listening {}", sim.getComName());

                while (true) {
                    portManager.withPort(sim.getComName(), helper -> {
                        try {
                            var smsList = helper.listAllSmsText(5000);
                            for (var rec : smsList) {
                                if (rec.body != null && containsOtp(rec.body)) {
                                    processSms(sim, rec);
                                    if (rec.index != null) {
                                        helper.deleteSms(rec.index);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("❌ Error scanning SMS {}: {}", sim.getComName(), e.getMessage(), e);
                        }
                        return null;
                    }, 8000L);

                    // --- nghỉ 7 giây trước lần scan tiếp theo ---
                    Thread.sleep(7000);

                    // stop nếu không còn session active
                    if (activeSessions.getOrDefault(sim.getId(), List.of())
                            .stream().noneMatch(RentSession::isActive)) {
                        log.info("🛑 Stop listener for {}", sim.getComName());
                        runningListeners.remove(sim.getId());
                        return;
                    }
                }
            } catch (Exception e) {
                log.error("❌ Listener error {}: {}", sim.getComName(), e.getMessage(), e);
                runningListeners.remove(sim.getId());
            }
        }).start();
    }


    // === Xử lý SMS nhận về ===
    private void processSms(Sim sim, AtCommandHelper.SmsRecord rec) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        if (sessions.isEmpty()) return;

        String smsNorm = normalize(rec.body);
        String otp = extractOtp(rec.body);
        if (otp == null) return;

        boolean matched = false;

        for (RentSession s : sessions) {
            if (!s.isActive()) continue;

            for (String service : s.getServices()) {
                if (smsNorm.contains(normalize(service))) {
                    forwardToSocket(sim, s, service, rec, otp);
                    matched = true;
                }
            }
        }

        // fallback nếu không match service nhưng có OTP
        if (!matched) {
            RentSession first = sessions.stream().filter(RentSession::isActive).findFirst().orElse(null);
            if (first != null) {
                String service = first.getServices().isEmpty() ? "UNKNOWN" : first.getServices().get(0);
                log.info("↪️ Fallback forward with service={}", service);
                forwardToSocket(sim, first, service, rec, otp);
            }
        }

        // remove session hết hạn
        sessions.removeIf(s -> !s.isActive());
    }

    // === Forward OTP lên remote socket ===
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

    private boolean containsOtp(String content) {
        return content.matches(".*\\b\\d{4,8}\\b.*");
    }

    private String extractOtp(String content) {
        Matcher m = Pattern.compile("\\b\\d{4,8}\\b").matcher(content);
        return m.find() ? m.group() : null;
    }

    private String generateOtp() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100000, 999999));
    }

    // === RentSession class ===
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
