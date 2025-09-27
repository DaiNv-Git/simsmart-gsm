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

    // Start listener cho 1 COM
    public void startListener(Sim sim) {
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

                    // Bật chế độ text SMS
                    out.write("AT+CMGF=1\r".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(500);

                    // Vòng lặp đọc tin nhắn
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
                    }
                }
            } catch (Exception e) {
                log.error("Listener error on {}: {}", sim.getComName(), e.getMessage(), e);
            }
        }).start();
    }

    // Khi user thuê dịch vụ
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country) {
        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes, country);
        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("Added rent session: {}", session);
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
