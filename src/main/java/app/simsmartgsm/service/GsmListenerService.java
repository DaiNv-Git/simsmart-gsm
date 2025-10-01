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

    /** comName -> PortWorker */
    private final Map<String, PortWorker> workers = new ConcurrentHashMap<>();
    /** simId -> sessions */
    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();

    private final boolean testMode = true;
    private final RestTemplate restTemplate = new RestTemplate();

    /** Dùng chung cho retry worker và refund schedule */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Value("${gsm.order-api.base-url}")
    private String orderApiBaseUrl;

    // === Thuê SIM ===
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country, String orderId, String type) {

        RentSession session = new RentSession(
                accountId,
                services != null ? services : List.of(),
                Instant.now(),
                Math.max(1, durationMinutes), // tránh 0
                country,
                orderId,
                OtpSessionType.fromString(type),
                false
        );
        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);

        log.info("➕ Rent SIM {} by acc={} services={} duration={}m type={}",
                sim.getPhoneNumber(), accountId, services, durationMinutes, session.getType());

        startWorkerForSim(sim); // an toàn nếu đã có

        // Hết hạn thì check/refund
        scheduler.schedule(() -> checkAndRefund(sim, session),
                session.getDurationMinutes(), TimeUnit.MINUTES);

        // --- TEST MODE ---
        if (testMode && !session.getServices().isEmpty()) {
            String service = session.getServices().get(0);
            scheduler.execute(() -> {
                try {
                    Thread.sleep(1800);
                    String otp = generateOtp();
                    String fakeSms = service.toUpperCase(Locale.ROOT) + " OTP " + otp;

                    AtCommandHelper.SmsRecord rec = new AtCommandHelper.SmsRecord();
                    rec.sender = "TEST-SENDER";
                    rec.body = fakeSms;

                    log.info("📩 [TEST MODE] Fake incoming SMS({}): {}", sim.getComName(), rec.body);
                    processSms(sim, rec);
                } catch (Exception e) {
                    log.error("❌ Error in test SMS thread: {}", e.getMessage(), e);
                }
            });
        }
    }

    private void checkAndRefund(Sim sim, RentSession session) {
        if (session.isActive()) return; // vẫn còn hiệu lực -> không refund

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

        // Loại session đã hết hạn khỏi danh sách
        endSession(sim, session, "expire");
    }

    // === Worker cho SIM ===
    private void startWorkerForSim(Sim sim) {
        String com = sim.getComName();
        workers.compute(com, (k, existing) -> {
            if (existing != null) {
                // đã có worker đang chạy
                return existing;
            }
            // tạo worker mới, worker tự lo open port bên trong (PortWorker)
            PortWorker worker = new PortWorker(sim, 4000, this);
            Thread t = new Thread(worker, "PortWorker-" + com);
            t.setDaemon(true);
            t.start();
            log.info("▶️ Worker started for {}", com);

            // Lên lịch “health check” nhẹ để nếu worker bị stop vì port lỗi thì thử khởi tạo lại
            scheduleReconnectIfNeeded(sim, 5, TimeUnit.SECONDS);
            return worker;
        });
    }

    /** Đặt lịch kiểm tra lại worker và thử tạo lại nếu bị mất. */
    private void scheduleReconnectIfNeeded(Sim sim, long delay, TimeUnit unit) {
        scheduler.schedule(() -> {
            String com = sim.getComName();
            PortWorker w = workers.get(com);
            // Nếu không còn worker nhưng vẫn còn session active -> tạo lại
            if (w == null && hasAnyActiveSession(sim.getId())) {
                log.warn("♻️ No worker for {}, trying to restart", com);
                startWorkerForSim(sim);
            }
            // Nếu còn session active, tiếp tục theo dõi
            if (hasAnyActiveSession(sim.getId())) {
                scheduleReconnectIfNeeded(sim, 10, TimeUnit.SECONDS);
            }
        }, delay, unit);
    }

    private boolean hasAnyActiveSession(String simId) {
        List<RentSession> sessions = activeSessions.get(simId);
        if (sessions == null || sessions.isEmpty()) return false;
        return sessions.stream().anyMatch(RentSession::isActive);
    }

    // === Gửi SMS ===
    public void sendSms(Sim sim, String to, String content) {
        PortWorker w = workers.get(sim.getComName());
        if (w != null) {
            w.sendSms(to, content);
        } else {
            log.warn("⚠️ No worker for sim {}. Queue ignored", sim.getComName());
        }
    }

    // === Xử lý SMS nhận về ===
    public void processSms(Sim sim, AtCommandHelper.SmsRecord rec) {
        List<RentSession> sessions = new ArrayList<>(
                activeSessions.getOrDefault(sim.getId(), List.of())
        );
        if (sessions.isEmpty()) return;

        String otp = extractOtp(rec.body);
        if (otp == null) return;

        String smsNorm = normalize(rec.body);
        boolean matched = false;

        for (RentSession s : sessions) {
            if (!s.isActive()) continue;
            for (String service : s.getServices()) {
                String serviceNorm = normalize(service);
                String servicePrefix = serviceNorm.substring(0, Math.min(4, serviceNorm.length()));
                if (smsNorm.startsWith(servicePrefix)) {
                    handleOtpReceived(sim, s, service, rec, otp);
                    matched = true;
                    break;
                }
            }
            if (matched) break; // tránh xử lý trùng
        }

        if (!matched) {
            // fallback: pick session active đầu tiên
            RentSession first = sessions.stream().filter(RentSession::isActive).findFirst().orElse(null);
            if (first != null) {
                handleOtpReceived(sim, first,
                        first.getServices().isEmpty() ? "UNKNOWN" : first.getServices().get(0),
                        rec, otp);
            }
        }
    }

    // === Xử lý khi nhận OTP ===
    private void handleOtpReceived(Sim sim, RentSession s, String service, AtCommandHelper.SmsRecord rec, String otp) {
        if (s.isOtpReceived()) {
            log.info("⚠️ Order {} đã SUCCESS trước đó, bỏ qua OTP mới", s.getOrderId());
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

        log.info("💾 Saved SMS to DB orderId={} simPhone={} otp={} duration={}m type={}",
                sms.getOrderId(), sms.getSimPhone(), otp, sms.getDurationMinutes(), s.getType());

        // Gọi update success duy nhất 1 lần
        try {
            callUpdateSuccessApi(s.getOrderId());
            s.setOtpReceived(true);
        } catch (Exception e) {
            log.error("❌ Error calling update success API for orderId={}", s.getOrderId(), e);
        }

        // Forward OTP lên remote socket
        forwardOtpToRemote(sim, s, resolvedServiceCode, rec, otp);

        // Phân biệt RENT vs BUY
        if (s.getType() == OtpSessionType.BUY) {
            // BUY: đóng session ngay
            endSession(sim, s, "buy-first-otp");
        }
        // RENT: giữ session đến hết hạn; không làm gì thêm
    }

    private void forwardOtpToRemote(Sim sim, RentSession s, String resolvedServiceCode,
                                    AtCommandHelper.SmsRecord rec, String otp) {
        Map<String, Object> wsMessage = new HashMap<>();
        wsMessage.put("deviceName", sim.getDeviceName());
        wsMessage.put("phoneNumber", sim.getPhoneNumber());
        wsMessage.put("comNumber", sim.getComName());
        wsMessage.put("customerId", s.getAccountId());
        wsMessage.put("serviceCode", resolvedServiceCode);
        wsMessage.put("countryName", s.getCountry() != null ? s.getCountry().getCountryCode() : null);
        wsMessage.put("smsContent", rec.body);
        wsMessage.put("fromNumber", rec.sender);
        wsMessage.put("otp", otp);

        try {
            StompSession stompSession = remoteStompClientConfig.getSession();
            if (stompSession != null && stompSession.isConnected()) {
                stompSession.send("/topic/receive-otp", wsMessage);
                log.info("📤 Forward OTP [{}] for acc={} service={} -> remote", otp, s.getAccountId(), resolvedServiceCode);
            } else {
                log.warn("⚠️ Remote not connected, cannot forward OTP (service={}, otp={})", resolvedServiceCode, otp);
            }
        } catch (Exception ex) {
            log.error("❌ Failed to forward OTP to remote: {}", ex.getMessage(), ex);
        }
    }

    // === Kết thúc session + stop worker nếu hết việc ===
    private void endSession(Sim sim, RentSession session, String reason) {
        List<RentSession> list = activeSessions.get(sim.getId());
        if (list != null) {
            list.remove(session);
            if (list.isEmpty()) {
                activeSessions.remove(sim.getId());
            }
        }
        log.info("⛔ End session orderId={} reason={}", session.getOrderId(), reason);
        stopWorkerIfNoActiveSession(sim);
    }

    // === Call API update success/refund ===
    private void callUpdateSuccessApi(String orderId) {
        String url = orderApiBaseUrl + "api/otp/order/" + orderId + "/success";
        restTemplate.postForEntity(url, null, Void.class);
    }

    private void callUpdateRefundApi(String orderId) {
        String url = orderApiBaseUrl + "api/otp/order/" + orderId + "/refund";
        restTemplate.postForEntity(url, null, Void.class);
    }

    public boolean hasWorker(String comPort) {
        return workers.containsKey(comPort);
    }
    // === Utils ===
    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", "");
    }

    private String extractOtp(String content) {
        if (content == null) return null;
        Matcher m = Pattern.compile("\\b\\d{4,8}\\b").matcher(content);
        return m.find() ? m.group() : null;
    }

    private void stopWorkerIfNoActiveSession(Sim sim) {
        boolean hasActive = hasAnyActiveSession(sim.getId());
        if (!hasActive) {
            PortWorker w = workers.remove(sim.getComName());
            if (w != null) {
                try {
                    w.stop(); // PortWorker cần implement stop()
                } catch (Exception ignored) {}
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
