package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;

import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.SmsMessageRepository;
import app.simsmartgsm.uitils.AtCommandHelper;
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
    private final SmsMessageRepository smsMessageRepository;

    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();
    private final Set<String> runningListeners = ConcurrentHashMap.newKeySet();
    private final Set<String> sentOtpSimIds = ConcurrentHashMap.newKeySet();

    // === Rent SIM session ===
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country) {
        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes, country);
        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("➕ Added rent session: {}", session);

        if (!runningListeners.contains(sim.getId())) {
            startListener(sim);
        }

        final String receiverPort = sim.getComName();

        // auto-send OTP test nếu có service
        if (!services.isEmpty()) {
            String service = services.get(0);
            String key = sim.getId() + ":" + service.toLowerCase();

            if (sentOtpSimIds.add(key)) {
                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        String otp = generateOtp();
                        String msg = "[TEST] " + service.toUpperCase() + " OTP " + otp;

                        log.info("📤 [INIT TEST] Sending SMS from {} -> {}: [{}]",
                                receiverPort, sim.getPhoneNumber(), msg);

                        boolean ok = portManager.withPort(receiverPort, helper -> {
                            try {
                                return helper.sendTextSms(sim.getPhoneNumber(), msg, Duration.ofSeconds(30));
                            } catch (Exception e) {
                                log.error("❌ Error sending OTP on {}: {}", receiverPort, e.getMessage());
                                return false;
                            }
                        }, 15000);

                        if (ok) {
                            log.info("📤 [INIT TEST] Result: ✅ Sent successfully");
                        } else {
                            log.warn("📤 [INIT TEST] Result: ❌ Failed to send");
                        }
                    } catch (Exception e) {
                        log.error("❌ Error auto-sending SMS: {}", e.getMessage(), e);
                    }
                }).start();
            }
        }
    }

    private String generateOtp() {
        int otp = ThreadLocalRandom.current().nextInt(100000, 999999);
        return String.valueOf(otp);
    }

    // === Start listener on COM ===
    private void startListener(Sim sim) {
        if (!runningListeners.add(sim.getId())) {
            log.info("Listener already running for sim {}", sim.getId());
            return;
        }

        new Thread(() -> {
            try {
                log.info("📡 Listener starting on {}...", sim.getComName());

                while (true) {
                    portManager.withPort(sim.getComName(), helper -> {
                        try {
                            helper.setTextMode(true);
                            helper.setNewMessageIndicationDefault();

                            // đọc tất cả SMS
                            var smsList = helper.listAllSmsText(5000);
                            if (!smsList.isEmpty()) {
                                for (var rec : smsList) {
                                    processSmsResponse(sim, rec);
                                    // xoá SMS sau khi xử lý để không trùng
                                    if (rec.index != null) {
                                        helper.deleteSms(rec.index);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("❌ Error reading SMS on {}: {}", sim.getComName(), e.getMessage());
                        }
                        return null;
                    }, 8000L);

                    Thread.sleep(3000);

                    // Nếu không còn session nào active => stop listener
                    if (activeSessions.getOrDefault(sim.getId(), List.of())
                            .stream().noneMatch(RentSession::isActive)) {
                        log.info("🛑 No active sessions, stopping listener for sim {}", sim.getId());
                        runningListeners.remove(sim.getId());
                        return;
                    }
                }
            } catch (Exception e) {
                log.error("❌ Listener error on {}: {}", sim.getComName(), e.getMessage(), e);
                runningListeners.remove(sim.getId());
            }
        }).start();
    }

    private void processSmsResponse(Sim sim, AtCommandHelper.SmsRecord rec) {
        try {
            if (rec.body == null || rec.body.isBlank()) {
                log.warn("⚠️ Empty SMS body on {} index={}", sim.getComName(), rec.index);
                return;
            }

            log.info("✅ Parsed SMS from {} content={}", rec.sender, rec.body);

            boolean exists = smsMessageRepository
                    .findByFromPhoneAndToPhoneAndMessageAndType(
                            rec.sender,
                            sim.getPhoneNumber(),
                            rec.body,
                            "INBOUND"
                    ).isPresent();

            if (!exists) {
                SmsMessage smsEntity = SmsMessage.builder()
                        .deviceName(sim.getDeviceName())
                        .fromPort(sim.getComName())
                        .fromPhone(rec.sender)
                        .toPhone(sim.getPhoneNumber())
                        .message(rec.body)
                        .modemResponse(rec.toString())
                        .type("INBOUND")
                        .timestamp(Instant.now())
                        .build();

                smsMessageRepository.save(smsEntity);
                log.info("💾 Saved new SMS to DB and forwarding...");

                routeMessage(sim, rec);
            } else {
                log.debug("⚠️ Duplicate SMS ignored: {}", rec.body);
            }
        } catch (Exception e) {
            log.error("❌ Error processing SMS on {}: {}", sim.getComName(), e.getMessage(), e);
        }
    }

    // === Route OTP tới remote broker ===
    private void routeMessage(Sim sim, AtCommandHelper.SmsRecord rec) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        if (sessions.isEmpty()) return;

        String contentNorm = normalize(rec.body);
        boolean forwarded = false;

        for (RentSession s : sessions) {
            if (!s.isActive()) continue;

            for (String service : s.getServices()) {
                String serviceNorm = normalize(service);
                if (contentNorm.contains(serviceNorm) && containsOtp(rec.body)) {
                    forwarded |= forwardToSocket(sim, s, service, rec);
                }
            }
        }

        if (!forwarded && containsOtp(rec.body)) {
            RentSession firstActive = sessions.stream().filter(RentSession::isActive).findFirst().orElse(null);
            if (firstActive != null) {
                String service = firstActive.getServices().isEmpty() ? "UNKNOWN" : firstActive.getServices().get(0);
                log.info("↪️ Fallback forward with service='{}'", service);
                forwardToSocket(sim, firstActive, service, rec);
            }
        }

        sessions.removeIf(s -> !s.isActive());
    }

    private boolean forwardToSocket(Sim sim, RentSession s, String service, AtCommandHelper.SmsRecord rec) {
        String otp = extractOtp(rec.body);
        if (otp == null) return false;

        Map<String, Object> wsMessage = new HashMap<>();
        wsMessage.put("deviceName", sim.getDeviceName());
        wsMessage.put("phoneNumber", sim.getPhoneNumber());
        wsMessage.put("comNumber", sim.getComName());
        wsMessage.put("customerId", s.getAccountId());
        wsMessage.put("serviceCode", service);
        wsMessage.put("waitingTime", s.getDurationMinutes());
        wsMessage.put("countryName", s.getCountry().getCountryCode());
        wsMessage.put("smsContent", rec.body);
        wsMessage.put("fromNumber", rec.sender);
        wsMessage.put("otp", otp);

        StompSession session = remoteStompClientConfig.getSession();
        if (session != null && session.isConnected()) {
            session.send("/topic/receive-otp", wsMessage);
            log.info("📤 Forwarded OTP [{}] for customer {} service={} -> remote",
                    otp, s.getAccountId(), service);
            return true;
        } else {
            log.warn("⚠️ Remote session not connected, cannot forward OTP (service={}, otp={})",
                    service, otp);
            return false;
        }
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", "");
    }

    private boolean containsOtp(String content) {
        return content.matches(".*\\b\\d{4,8}\\b.*");
    }

    private String extractOtp(String content) {
        Matcher m = Pattern.compile("\\b\\d{4,8}\\b").matcher(content);
        return m.find() ? m.group() : null;
    }

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
