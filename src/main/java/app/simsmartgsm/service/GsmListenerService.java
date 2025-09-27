package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.config.SmsParser;
import app.simsmartgsm.dto.response.SmsMessageUser;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import com.fazecast.jSerialComm.SerialPort;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.stomp.StompSession;

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

    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();
    private final Set<String> runningListeners = ConcurrentHashMap.newKeySet();

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

        for (String service : services) {
            new Thread(() -> {
                try {
                    for (int i = 1; i <= 2; i++) {   // ✅ chỉ gửi 3 lần
                        Thread.sleep(2000); // đợi trước mỗi lần gửi

                        String otp = generateOtp(6);
                        String msg = service.toUpperCase() + " OTP " + otp;

                        log.info("📤 [TEST {}] Sending SMS from {} -> {}: [{}]",
                                i, senderPort, sim.getPhoneNumber(), msg);

                        boolean ok = smsSenderService.sendSms(senderPort, sim.getPhoneNumber(), msg);
                        log.info("📤 [TEST {}] Result: {}", i, ok);
                    }
                } catch (Exception e) {
                    log.error("❌ Error auto-sending SMS: {}", e.getMessage(), e);
                }
            }).start();
        }
    }

    private String pickSenderPort(String receiverPort) {
        String configured = "COM110"; // cấu hình sẵn 1 port gửi test
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

                    out.write("AT+CMGF=1\r".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(500);

                    while (true) {
                        // 🔄 Đọc tất cả SMS thay vì chỉ unread
                        out.write("AT+CMGL=\"ALL\"\r".getBytes(StandardCharsets.US_ASCII));
                        out.flush();

                        byte[] buf = new byte[4096];
                        int len = in.read(buf);
                        if (len > 0) {
                            String resp = new String(buf, 0, len, StandardCharsets.US_ASCII);
                            log.debug("📥 Raw SMS buffer: \n{}", resp);

                            SmsMessageUser sms = SmsParser.parse(resp);
                            if (sms != null) {
                                log.info("✅ Parsed SMS from {} content={}", sms.getFrom(), sms.getContent());
                                routeMessage(sim, sms);
                            }
                        }

                        Thread.sleep(3000);

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

    // === Route OTP tới remote broker ===
    private void routeMessage(Sim sim, SmsMessageUser sms) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        for (RentSession s : sessions) {
            if (s.isActive()) {
                for (String service : s.getServices()) {
                    if (sms.getContent().toLowerCase().contains(service.toLowerCase())
                            && containsOtp(sms.getContent())) {

                        String otp = extractOtp(sms.getContent());
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
                        } else {
                            log.warn("⚠️ Remote session not connected, cannot forward OTP");
                        }
                    }
                }
            }
        }
        sessions.removeIf(s -> !s.isActive());
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
