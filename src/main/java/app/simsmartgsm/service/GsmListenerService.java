package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.config.SmsParser;
import app.simsmartgsm.dto.response.SmsMessageUser;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class GsmListenerService {

    private final RemoteStompClientConfig remoteStompClientConfig;
    private final PortManager portManager;

    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();
    private final Set<String> runningListeners = ConcurrentHashMap.newKeySet();
    private final Set<String> forwardedCache = ConcurrentHashMap.newKeySet();

    // === Rent SIM session ===
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country) {
        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes, country);
        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("➕ Added rent session: {}", session);

        if (!runningListeners.contains(sim.getId())) {
            startListener(sim);
        }
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
                            // Lấy tin nhắn chưa đọc
                            String resp = helper.sendAndRead("AT+CMGL=\"REC UNREAD\"", 5000);

                            if (resp != null && resp.contains("+CMGL:")) {
                                String[] blocks = resp.split("\\+CMGL:");
                                for (String block : blocks) {
                                    if (block.isBlank()) continue;
                                    String smsResp = "+CMGL:" + block.trim();

                                    SmsMessageUser sms = SmsParser.parse(smsResp);
                                    if (sms != null) {
                                        log.info("✅ Parsed SMS from={} content={}", sms.getFrom(), sms.getContent());

                                        // Forward
                                        routeMessage(sim, sms);

                                        // Xoá sau khi xử lý
                                        int idx = extractSmsIndex(smsResp);
                                        if (idx > 0) {
                                            helper.sendAndRead("AT+CMGD=" + idx, 2000);
                                            log.debug("🗑️ Deleted SMS index {}", idx);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("❌ Error on {}: {}", sim.getComName(), e.getMessage());
                        }
                        return null;
                    }, 5000L);

                    Thread.sleep(2000);

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
        }, "listener-" + sim.getComName()).start();
    }

    // === Route OTP tới remote broker ===
    private void routeMessage(Sim sim, SmsMessageUser sms) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        if (sessions.isEmpty()) return;

        String otp = extractOtp(sms.getContent());
        if (otp == null) return;

        String cacheKey = sim.getId() + "|" + sms.getContent() + "|" + otp;
        if (!forwardedCache.add(cacheKey)) {
            log.debug("⚠️ Duplicate forward ignored: {}", cacheKey);
            return;
        }

        String contentNorm = normalize(sms.getContent());
        boolean forwarded = false;

        for (RentSession s : sessions) {
            if (!s.isActive()) continue;

            for (String service : s.getServices()) {
                String serviceNorm = normalize(service);
                String servicePrefix = serviceNorm.length() >= 3 ? serviceNorm.substring(0, 3) : serviceNorm;
                String contentPrefix = contentNorm.length() >= 3 ? contentNorm.substring(0, 3) : contentNorm;

                if (contentNorm.contains(serviceNorm) || servicePrefix.equals(contentPrefix)) {
                    forwarded = forwardToSocket(sim, s, service, sms) || forwarded;
                }
            }
        }

        if (!forwarded) {
            log.warn("⚠️ OTP found but no matching service for SMS='{}'", sms.getContent());

            RentSession firstActive = sessions.stream()
                    .filter(RentSession::isActive)
                    .findFirst()
                    .orElse(null);

            if (firstActive != null) {
                forwardToSocket(sim, firstActive, "UNKNOWN", sms);
            }
        }

        sessions.removeIf(s -> !s.isActive());
    }

    private boolean forwardToSocket(Sim sim, RentSession s, String service, SmsMessageUser sms) {
        String otp = extractOtp(sms.getContent());
        if (otp == null) return false;

        Map<String, Object> wsMessage = new HashMap<>();
        wsMessage.put("deviceName", sim.getDeviceName());
        wsMessage.put("phoneNumber", sim.getPhoneNumber());
        wsMessage.put("comNumber", sim.getComName());
        wsMessage.put("customerId", s.getAccountId());
        wsMessage.put("serviceCode", service);
        wsMessage.put("waitingTime", s.getDurationMinutes());
        wsMessage.put("countryName", s.getCountry().getCountryCode());
        wsMessage.put("smsContent", sms.getContent());
        wsMessage.put("fromNumber", sms.getFrom());
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

    private int extractSmsIndex(String resp) {
        try {
            Matcher m2 = Pattern.compile("\\+CMGL:\\s*(\\d+),").matcher(resp);
            if (m2.find()) return Integer.parseInt(m2.group(1));
        } catch (Exception ignored) {}
        return -1;
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", "");
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
