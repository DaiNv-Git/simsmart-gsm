package app.simsmartgsm.service;

import app.simsmartgsm.config.SmsParser;
import app.simsmartgsm.dto.response.SmsMessageUser;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.fazecast.jSerialComm.SerialPort;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class GsmListenerService {

    private final SimpMessagingTemplate messagingTemplate;

    // Map quản lý session thuê: simId -> list session
    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();
    private final Set<String> runningListeners = ConcurrentHashMap.newKeySet();

    // Start listener cho 1 COM
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
                    log.error("Cannot open port {}", sim.getComName());
                    runningListeners.remove(sim.getId());
                    return;
                }
                try (InputStream in = port.getInputStream();
                     OutputStream out = port.getOutputStream()) {

                    out.write("AT+CMGF=1\r".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(500);

                    while (true) {
                        out.write("AT+CMGL=\"REC UNREAD\"\r".getBytes(StandardCharsets.US_ASCII));
                        out.flush();

                        byte[] buf = new byte[2048];
                        int len = in.read(buf);
                        if (len > 0) {
                            String resp = new String(buf, 0, len, StandardCharsets.US_ASCII);
                            log.info("Raw SMS resp: {}", resp);

                            SmsMessageUser sms = SmsParser.parse(resp);
                            if (sms != null) {
                                routeMessage(sim, sms);
                            }
                        }

                        Thread.sleep(2000);

                        // Nếu không còn session nào active → thoát thread
                        if (activeSessions.getOrDefault(sim.getId(), List.of())
                                .stream().noneMatch(RentSession::isActive)) {
                            log.info("No active sessions, stopping listener for sim {}", sim.getId());
                            runningListeners.remove(sim.getId());
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Listener error on {}: {}", sim.getComName(), e.getMessage(), e);
                runningListeners.remove(sim.getId());
            }
        }).start();
    }

    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country) {
        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes, country);
        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("Added rent session: {}", session);
        simulateSmsForSession(sim, session);
    }

    // Route SMS tới đúng KH thuê dịch vụ
    private void routeMessage(Sim sim, SmsMessageUser sms) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());

        for (RentSession s : sessions) {
            if (s.isActive()) {
                boolean matched = false;

                for (String service : s.getServices()) {
                    if (sms.getContent().toLowerCase().contains(service.toLowerCase())
                            && containsOtp(sms.getContent())) {
                        matched = true;
                        break;
                    }
                }

                if (matched) {
                    Map<String, Object> wsMessage = new HashMap<>();
                    wsMessage.put("deviceName", sim.getDeviceName());
                    wsMessage.put("phoneNumber", sim.getPhoneNumber());
                    wsMessage.put("comNumber", sim.getComName());
                    wsMessage.put("customerId", s.getAccountId());
                    wsMessage.put("serviceCode", String.join(",", s.getServices()));
                    wsMessage.put("waitingTime", s.getDurationMinutes());
                    wsMessage.put("countryName", s.getCountry().getCountryCode());
                    wsMessage.put("smsContent", sms.getContent());
                    wsMessage.put("fromNumber", sms.getFrom());

                    messagingTemplate.convertAndSend("/topic/send-otp", wsMessage);
                    log.info("Forwarded SMS [{}] to customer {} (services={})",
                            sms.getContent(), s.getAccountId(), s.getServices());
                }
            }
        }

        sessions.removeIf(s -> !s.isActive());
    }
    // Start cuộc gọi (song song với listener)
    public void startCall(Sim sim, String targetNumber, int durationSec) {
        new Thread(() -> {
            try {
                SerialPort port = SerialPort.getCommPort(sim.getComName());
                port.setBaudRate(115200);
                if (!port.openPort()) {
                    log.error("Cannot open port {}", sim.getComName());
                    return;
                }
                try (InputStream in = port.getInputStream();
                     OutputStream out = port.getOutputStream()) {

                    // Gọi ra số đích
                    String cmd = "ATD" + targetNumber + ";\r";
                    out.write(cmd.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    log.info("Calling {} from SIM {}", targetNumber, sim.getPhoneNumber());

                    long start = System.currentTimeMillis();

                    // Vòng lặp call + listen
                    while (System.currentTimeMillis() - start < durationSec * 1000L) {
                        byte[] buf = new byte[1024];
                        int len = in.read(buf);
                        if (len > 0) {
                            String resp = new String(buf, 0, len, StandardCharsets.US_ASCII);
                            log.info("Call session resp: {}", resp);

                            // Nếu có SMS tới trong lúc call
                            if (resp.contains("+CMTI")) {
                                log.info("SMS arrived during call for SIM {}", sim.getPhoneNumber());
                                // Chỉ log lại, đọc sau khi call xong
                            }
                        }
                    }

                    // Hết thời gian -> kết thúc call
                    out.write("ATH\r".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    log.info("Call ended for SIM {}", sim.getPhoneNumber());

                    // Sau khi call -> đọc SMS chưa đọc
                    out.write("AT+CMGL=\"REC UNREAD\"\r".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    byte[] buf = new byte[4096];
                    int len = in.read(buf);
                    if (len > 0) {
                        String resp = new String(buf, 0, len, StandardCharsets.US_ASCII);
                        log.info("Post-call SMS resp: {}", resp);

                        SmsMessageUser sms = SmsParser.parse(resp);
                        if (sms != null) {
                            routeMessage(sim, sms);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Call error on {}: {}", sim.getComName(), e.getMessage(), e);
            }
        }).start();
    }
    private void simulateSmsForSession(Sim sim, RentSession session) {
        new Thread(() -> {
            try {
                for (int i = 1; i <= 3; i++) {
                    // Tạo nội dung SMS giả lập
                    String fakeContent = session.getServices().get(0).toUpperCase()
                            + " OTP test " + (1000 + i);

                    SmsMessageUser sms = new SmsMessageUser("SYSTEM", fakeContent);

                    log.info("Simulating SMS {} for session {}", fakeContent, session.getAccountId());

                    // Gọi lại routeMessage như thể SMS thật
                    routeMessage(sim, sms);

                    Thread.sleep(2000); // delay 2s giữa các tin nhắn
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private boolean containsOtp(String content) {
        return content.matches(".*\\b\\d{4,8}\\b.*");
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
