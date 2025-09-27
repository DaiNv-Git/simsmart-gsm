package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.config.SmsParser;
import app.simsmartgsm.dto.response.SmsMessageUser;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.SmsMessageRepository;
import com.fazecast.jSerialComm.SerialPort;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

    private final SmsSenderService smsSenderService;
    private final RemoteStompClientConfig remoteStompClientConfig;

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
        final String senderPort = pickSenderPort(receiverPort);

        if (!services.isEmpty()) {
            String service = services.get(0);
            String key = sim.getId() + ":" + service.toLowerCase();
            if (sentOtpSimIds.add(key)) {
                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        String otp = generateOtp(6);
                        String msg = "[TEST] " + service.toUpperCase() + " OTP " + otp;

                        log.info("📤 [INIT TEST] Sending SMS from {} -> {}: [{}]",
                                senderPort, sim.getPhoneNumber(), msg);

                        boolean ok = smsSenderService.sendSms(senderPort, sim.getPhoneNumber(), msg);
                        log.info("📤 [INIT TEST] Result: {}", ok);
                    } catch (Exception e) {
                        log.error("❌ Error auto-sending SMS: {}", e.getMessage(), e);
                    }
                }).start();
            }
        }
    }

    private String pickSenderPort(String receiverPort) {
        String configured = "COM71"; // cấu hình sẵn 1 port gửi test
        if (configured != null && !configured.equalsIgnoreCase(receiverPort)) {
            return configured;
        }
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort p : ports) {
            if (!p.getSystemPortName().equalsIgnoreCase(receiverPort)) {
                return p.getSystemPortName();
            }
        }
        return receiverPort;
    }

    private String generateOtp(int len) {
        int min = (int) Math.pow(10, len - 1);
        int max = (int) Math.pow(10, len) - 1;
        return String.valueOf(new Random().nextInt(max - min + 1) + min);
    }

    // === Start listener on COM ===
    private void startListener(Sim sim) {
        if (!runningListeners.add(sim.getId())) {
            log.info("Listener already running for sim {}", sim.getId());
            return;
        }

        new Thread(() -> {
            try {
                SerialPort port = SerialPort.getCommPort(sim.getComName());
                port.setBaudRate(115200);
                if (!port.openPort()) {
                    log.error("❌ Cannot open port {}", sim.getComName());
                    runningListeners.remove(sim.getId());
                    return;
                }
                try (InputStream in = port.getInputStream();
                     OutputStream out = port.getOutputStream()) {

                    out.write("AT+CMGF=1\r".getBytes(StandardCharsets.US_ASCII)); // text mode
                    out.flush();
                    Thread.sleep(500);

                    out.write("AT+CNMI=2,1,0,0,0\r".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(500);

                    log.info("📡 Listener started on {} (waiting for SMS...)", sim.getComName());

                    byte[] buf = new byte[8192];

                    while (true) {
                        int len = in.read(buf);
                        if (len > 0) {
                            String resp = new String(buf, 0, len, StandardCharsets.US_ASCII).trim();
                            if (resp.isEmpty()) continue;

                            log.debug("📥 Raw modem resp ({}):\n{}", sim.getComName(), resp);

                            if (resp.contains("+CMTI")) {
                                int index = extractSmsIndex(resp);
                                if (index > 0) {
                                    log.info("📥 New SMS notification on {} at index {}", sim.getComName(), index);
                                    String cmd = "AT+CMGR=" + index + "\r";
                                    out.write(cmd.getBytes(StandardCharsets.US_ASCII));
                                    out.flush();

                                    Thread.sleep(500);
                                    int len2 = in.read(buf);
                                    if (len2 > 0) {
                                        String smsResp = new String(buf, 0, len2, StandardCharsets.US_ASCII);
                                        processSmsResponse(sim, smsResp);
                                    }
                                }
                            } else {
                                processSmsResponse(sim, resp);
                            }
                        }

                        Thread.sleep(2000);

                        if (activeSessions.getOrDefault(sim.getId(), List.of())
                                .stream().noneMatch(RentSession::isActive)) {
                            log.info("🛑 No active sessions, stopping listener for sim {}", sim.getId());
                            runningListeners.remove(sim.getId());
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("❌ Listener error on {}: {}", sim.getComName(), e.getMessage(), e);
                runningListeners.remove(sim.getId());
            }
        }).start();
    }

    private void processSmsResponse(Sim sim, String resp) {
        try {
            SmsMessageUser sms = SmsParser.parse(resp);
            if (sms != null) {
                if (sms.getContent().contains("[TEST]")) {
                    log.debug("⏭️ Skip test OTP: {}", sms.getContent());
                    return;
                }

                log.info("✅ Parsed SMS from {} content={}", sms.getFrom(), sms.getContent());

                boolean exists = smsMessageRepository
                        .findByFromPhoneAndToPhoneAndMessage(
                                sms.getFrom(),
                                sim.getPhoneNumber(),
                                sms.getContent()
                        ).isPresent();

                if (!exists) {
                    SmsMessage smsEntity = SmsMessage.builder()
                            .deviceName(sim.getDeviceName())
                            .fromPort(sim.getComName())
                            .fromPhone(sms.getFrom())
                            .toPhone(sim.getPhoneNumber())
                            .message(sms.getContent())
                            .modemResponse(resp)
                            .type("INBOUND")
                            .timestamp(Instant.now())
                            .build();

                    smsMessageRepository.save(smsEntity);
                    log.info("💾 Saved new SMS to DB and forwarding...");

                    routeMessage(sim, sms);
                } else {
                    log.debug("⚠️ Duplicate SMS ignored: {}", sms.getContent());
                }
            }
        } catch (Exception e) {
            log.error("❌ Error processing SMS: {}", e.getMessage(), e);
        }
    }

    /** Lấy index tin nhắn từ +CMTI hoặc +CMGL */
    private int extractSmsIndex(String resp) {
        try {
            Matcher m1 = Pattern.compile("\\+CMTI:\\s*\"\\w+\",(\\d+)").matcher(resp);
            if (m1.find()) return Integer.parseInt(m1.group(1));

            Matcher m2 = Pattern.compile("\\+CMGL:\\s*(\\d+),").matcher(resp);
            if (m2.find()) return Integer.parseInt(m2.group(1));
        } catch (Exception ignored) {}
        return -1;
    }

    // === Route OTP tới remote broker ===
    private void routeMessage(Sim sim, SmsMessageUser sms) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        if (sessions.isEmpty()) return;

        String contentNorm = normalize(sms.getContent());
        boolean forwarded = false;

        for (RentSession s : sessions) {
            if (!s.isActive()) continue;

            for (String service : s.getServices()) {
                String serviceNorm = normalize(service);
                if (contentNorm.contains(serviceNorm) && containsOtp(sms.getContent())) {
                    forwarded |= forwardToSocket(sim, s, service, sms);
                } else {
                    log.debug("❌ Not matched service='{}' content='{}'", service, sms.getContent());
                }
            }
        }

        if (!forwarded && containsOtp(sms.getContent())) {
            RentSession firstActive = sessions.stream().filter(RentSession::isActive).findFirst().orElse(null);
            if (firstActive != null) {
                String service = firstActive.getServices().isEmpty() ? "UNKNOWN" : firstActive.getServices().get(0);
                log.info("↪️ Fallback forward with service='{}' (no exact match).", service);
                forwardToSocket(sim, firstActive, service, sms);
            } else {
                log.warn("⚠️ Has OTP but no active session to forward.");
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
