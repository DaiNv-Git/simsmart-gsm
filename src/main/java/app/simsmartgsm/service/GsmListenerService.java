package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.config.SmsParser;
import app.simsmartgsm.dto.response.SmsMessageUser;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.SmsMessageRepository;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
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

    private final SmsSenderService smsSenderService;
    private final RemoteStompClientConfig remoteStompClientConfig;
    private final PortManager portManager;
    private final SmsMessageRepository smsMessageRepository;

    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();
    private final Set<String> runningListeners = ConcurrentHashMap.newKeySet();
    private final Set<String> sentOtpSimIds = ConcurrentHashMap.newKeySet();
    private final Set<String> forwardedCache = ConcurrentHashMap.newKeySet();

    // === Rent SIM ===
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country) {
        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes, country);
        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("➕ Added rent session: {}", session);

        if (!runningListeners.contains(sim.getId())) {
            startListener(sim);
        }

        // gửi thử OTP test 1 lần
        if (!services.isEmpty()) {
            String service = services.get(0);
            String key = sim.getId() + ":" + service.toLowerCase();
            if (sentOtpSimIds.add(key)) {
                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        String otp = generateOtp();
                        String msg = "[TEST] " + service.toUpperCase() + " OTP " + otp;
                        boolean ok = smsSenderService.sendSms(pickSenderPort(sim.getComName()), sim.getPhoneNumber(), msg);
                        log.info("📤 [INIT TEST] Send result={} msg={}", ok, msg);
                    } catch (Exception e) {
                        log.error("❌ Error auto-sending SMS: {}", e.getMessage(), e);
                    }
                }).start();
            }
        }
    }

    // === Start listener ===
    private void startListener(Sim sim) {
        if (!runningListeners.add(sim.getId())) return;

        new Thread(() -> {
            try {
                log.info("📡 Listener starting on {}...", sim.getComName());
                while (true) {
                    portManager.withPort(sim.getComName(), helper -> {
                        try {
                            helper.sendAndRead("AT+CMGF=1", 2000);
                            helper.sendAndRead("AT+CPMS=\"ME\",\"ME\",\"ME\"", 2000);
                            helper.sendAndRead("AT+CNMI=2,1,0,0,0", 2000);

                            String resp = helper.sendAndRead("AT+CMGL=\"REC UNREAD\"", 5000);
                            if (resp != null && resp.contains("+CMGL:")) {
                                for (String block : splitMessages(resp)) {
                                    SmsMessageUser sms = SmsParser.parse(block);
                                    if (sms != null) {
                                        processSms(sim, sms, block, helper);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("❌ Error on {}: {}", sim.getComName(), e.getMessage());
                        }
                        return null;
                    }, 5000L);

                    Thread.sleep(3000);

                    // stop nếu hết session
                    if (activeSessions.getOrDefault(sim.getId(), List.of())
                            .stream().noneMatch(RentSession::isActive)) {
                        log.info("🛑 Stop listener for sim {}", sim.getId());
                        runningListeners.remove(sim.getId());
                        return;
                    }
                }
            } catch (Exception e) {
                log.error("❌ Listener crashed on {}: {}", sim.getComName(), e.getMessage());
                runningListeners.remove(sim.getId());
            }
        }, "listener-" + sim.getComName()).start();
    }

    // === Handle 1 SMS ===
    private void processSms(Sim sim, SmsMessageUser sms, String raw, AtCommandHelper helper) throws IOException, InterruptedException {
        log.info("✅ Parsed SMS from={} content={}", sms.getFrom(), sms.getContent());

        String cacheKey = sim.getId() + "|" + sms.getContent();
        if (!forwardedCache.add(cacheKey)) {
            log.debug("⚠️ Duplicate ignored: {}", cacheKey);
            return;
        }

        // lưu DB
        SmsMessage smsEntity = SmsMessage.builder()
                .deviceName(sim.getDeviceName())
                .fromPort(sim.getComName())
                .fromPhone(sms.getFrom())
                .toPhone(sim.getPhoneNumber())
                .message(sms.getContent())
                .modemResponse(raw)
                .type("INBOUND")
                .timestamp(Instant.now())
                .build();
        smsMessageRepository.save(smsEntity);

        // xoá SMS khỏi SIM
        int idx = extractSmsIndex(raw);
        if (idx > 0) helper.sendAndRead("AT+CMGD=" + idx, 2000);

        // forward về broker
        routeMessage(sim, sms);
    }
    // === Route OTP tới remote broker ===
    private void routeMessage(Sim sim, SmsMessageUser sms) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        if (sessions.isEmpty()) return;

        String contentNorm = normalize(sms.getContent());
        String otp = extractOtp(sms.getContent());
        if (otp == null) {
            log.debug("⚠️ SMS không chứa OTP, bỏ qua: {}", sms.getContent());
            return;
        }

        boolean forwarded = false;

        for (RentSession s : sessions) {
            if (!s.isActive()) continue;

            for (String service : s.getServices()) {
                String serviceNorm = normalize(service);

                // So khớp full hoặc prefix 3 ký tự
                String servicePrefix = serviceNorm.length() >= 3 ? serviceNorm.substring(0, 3) : serviceNorm;
                String contentPrefix = contentNorm.length() >= 3 ? contentNorm.substring(0, 3) : contentNorm;

                if (contentNorm.contains(serviceNorm) || contentPrefix.equals(servicePrefix)) {
                    forwarded |= forwardToSocket(sim, s, service, sms);
                }
            }
        }

        // Nếu có OTP nhưng không match service nào → fallback
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

        // dọn session hết hạn
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

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", "");
    }

    private String extractOtp(String content) {
        Matcher m = Pattern.compile("\\b\\d{4,8}\\b").matcher(content);
        return m.find() ? m.group() : null;
    }

    // === Utility ===
    private List<String> splitMessages(String resp) {
        return Arrays.asList(resp.split("\\+CMGL:")); // mỗi tin bắt đầu bằng +CMGL
    }

    private String pickSenderPort(String receiverPort) {
        for (SerialPort p : SerialPort.getCommPorts()) {
            if (!p.getSystemPortName().equalsIgnoreCase(receiverPort)) return p.getSystemPortName();
        }
        return receiverPort;
    }

    private String generateOtp() {
        return String.format("%08d", ThreadLocalRandom.current().nextInt(100000000));
    }

    private int extractSmsIndex(String resp) {
        Matcher m = Pattern.compile("\\+CMGL:\\s*(\\d+),").matcher(resp);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // routeMessage & forwardToSocket giữ nguyên như Hoa viết
    // ...

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