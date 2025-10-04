package app.simsmartgsm.service;

import app.simsmartgsm.config.RemoteStompClientConfig;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.ServiceRepository;
import app.simsmartgsm.repository.SmsMessageRepository;
import app.simsmartgsm.uitils.AtCommandHelper;
import app.simsmartgsm.uitils.MarketingSessionRegistry;
import app.simsmartgsm.uitils.OtpSessionType;
import app.simsmartgsm.uitils.PortWorker;
import com.jcraft.jsch.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private final CallRecordService callRecordService;
    private final MarketingSessionRegistry marketingRegistry;

    private final Map<String, PortWorker> workers = new ConcurrentHashMap<>();
    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate = new RestTemplate();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Value("${gsm.test-mode:false}") private boolean testMode;
    @Value("${gsm.test-call-mode:false}") private boolean testCallMode;
    @Value("${gsm.loop-test-sms:false}") private boolean loopTestSms;
    @Value("${gsm.loop-test-sms-interval:30}") private int loopTestSmsInterval;
    @Value("${gsm.order-api.base-url}") private String orderApiBaseUrl;

    // SSH config
    @Value("${gsm.ssh.host}") private String sshHost;
    @Value("${gsm.ssh.port:22}") private int sshPort;
    @Value("${gsm.ssh.user}") private String sshUser;
    @Value("${gsm.ssh.password:}") private String sshPassword;
    @Value("${gsm.ssh.key-path:}") private String sshKeyPath;
    @Value("${gsm.ssh.key-passphrase:}") private String sshKeyPassphrase;

    @Value("${gsm.recording.local-temp:/tmp/recordings}") private String localRecordingDir;

    // === Rent SIM (SMS + CALL) ===
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country, String orderId, String type, Boolean record) {

        RentSession session = new RentSession(
                accountId, services, Instant.now(), durationMinutes,
                country, orderId, OtpSessionType.fromString(type),
                false, type, record != null && record, false, null, null
        );

        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("➕ Rent SIM {} by acc={} services={} duration={}m",
                sim.getPhoneNumber(), accountId, services, durationMinutes);

        startWorkerForSim(sim);
        scheduler.schedule(() -> checkAndRefund(sim, session), durationMinutes, TimeUnit.MINUTES);

        // test
        if (!services.isEmpty()) {
            String svc = services.get(0);
            if (testMode) {
                sendFakeSms(sim, svc);
                if (loopTestSms) {
                    scheduler.scheduleAtFixedRate(() -> sendFakeSms(sim, svc),
                            loopTestSmsInterval, loopTestSmsInterval, TimeUnit.SECONDS);
                }
            }
            if (testCallMode && "buy.call.service".equalsIgnoreCase(type)) {
                sendFakeCall(sim, session);
            }
        }
    }

    // === Fake SMS ===
    private void sendFakeSms(Sim sim, String service) {
        String otp = generateOtp();
        AtCommandHelper.SmsRecord rec = new AtCommandHelper.SmsRecord();
        rec.sender = "TEST-SENDER";
        rec.body = service.toUpperCase() + " OTP " + otp;
        log.info("📩 [TEST MODE] Fake incoming SMS: {}", rec.body);
        processSms(sim, rec);
    }

    // === Fake CALL ===
    private void sendFakeCall(Sim sim, RentSession session) {
        new Thread(() -> {
            try {
                Thread.sleep(1500);
                log.info("📞 [TEST CALL MODE] Fake incoming call tới {}", sim.getPhoneNumber());
                processIncomingCall(sim, "FAKE-NUMBER", session);
            } catch (Exception e) {
                log.error("❌ Error fake call: {}", e.getMessage(), e);
            }
        }).start();
    }

    // === Process SMS (OTP hoặc Marketing) ===
    public void processSms(Sim sim, AtCommandHelper.SmsRecord rec) {
        String smsNorm = normalize(rec.body);
        String otp = extractOtp(rec.body);

        // 1) Dò session OTP nếu có
        RentSession matchedSession = null;
        String resolvedServiceCode = "UNKNOWN";
        List<RentSession> sessions = new ArrayList<>(activeSessions.getOrDefault(sim.getId(), List.of()));

        for (RentSession s : sessions) {
            if (!s.isActive()) continue;
            for (String service : s.getServices()) {
                String serviceNorm = normalize(service);
                String servicePrefix = serviceNorm.substring(0, Math.min(4, serviceNorm.length()));
                if (smsNorm.startsWith(servicePrefix)) {
                    matchedSession = s;
                    resolvedServiceCode = serviceRepository.findByCode(service).map(v -> v.getCode()).orElse(service);
                    break;
                }
            }
            if (matchedSession != null) break;
        }

        // 2) Luôn lưu INBOX vào DB
        SmsMessage sms = new SmsMessage();
        sms.setOrderId(matchedSession != null ? matchedSession.getOrderId() : null);
        sms.setAccountId(matchedSession != null ? matchedSession.getAccountId() : null);
        sms.setDurationMinutes(matchedSession != null ? matchedSession.getDurationMinutes() : 0);
        sms.setDeviceName(sim.getDeviceName());
        sms.setComPort(sim.getComName());
        sms.setSimPhone(sim.getPhoneNumber());
        sms.setServiceCode(resolvedServiceCode);
        sms.setFromNumber(rec.sender);
        sms.setToNumber(sim.getPhoneNumber());
        sms.setContent(rec.body);
        sms.setModemResponse("OK");
        sms.setType("INBOX");
        sms.setServiceType(matchedSession != null ? matchedSession.getServiceType() : "UNKNOWN");
        sms.setTimestamp(Instant.now());
        smsMessageRepository.save(sms);
        log.info("💾 Saved SMS INBOX to DB: {}", sms.getId());

        // 3) Nếu có session OTP và có OTP → xử lý OTP
        if (matchedSession != null && otp != null) {
            handleOtpReceived(sim, matchedSession, rec, otp, resolvedServiceCode);
            return; // OTP xử lý xong thì không cần push chat nữa
        }

        // 4) Nếu campaign 2 chiều → phải đúng KH mới push
        MarketingSessionRegistry.TwoWaySession mkt = marketingRegistry.lookup(sim.getPhoneNumber(), rec.sender);

        if (mkt != null && rec.sender.equals(mkt.getCustomerNumber())) {
            Map<String, Object> chat = new HashMap<>();
            chat.put("phoneNumber", sim.getPhoneNumber());   // số SIM
            chat.put("fromNumber", rec.sender);              // KH trả lời
            chat.put("content", rec.body);
            chat.put("campaignId", mkt.getCampaignId());
            chat.put("sessionId",  mkt.getSessionId());

            StompSession stompSession = remoteStompClientConfig.getSession();
            if (stompSession != null && stompSession.isConnected()) {
                stompSession.send("/topic/chat/phone", chat);
                log.info("📡 Sent WS /topic/chat/phone (2-way): {}", chat);
            }
        } else {
            log.info("🚫 Bỏ qua SMS không thuộc session OTP hoặc campaign hợp lệ: {} từ {}", rec.body, rec.sender);
        }
    }

    private void handleOtpReceived(Sim sim, RentSession s, AtCommandHelper.SmsRecord rec,
                                   String otp, String resolvedServiceCode) {
        boolean isBuyOtp = "buy.otp.service".equalsIgnoreCase(s.getServiceType());

        log.info("💾 OTP matched orderId={} sim={} otp={} serviceType={}",
                s.getOrderId(), sim.getPhoneNumber(), otp, s.getServiceType());

        // 1) Notify success API (chỉ 1 lần cho session)
        if (!s.isOtpReceived()) {
            try {
                callUpdateSuccessApi(s.getOrderId());
                s.setOtpReceived(true);
                log.info("✅ Đã gọi success API cho orderId={}", s.getOrderId());
            } catch (Exception e) {
                log.error("❌ Error update success API orderId={}", s.getOrderId(), e);
            }
        }

        // 2) Push OTP về socket cho UI
        Map<String, Object> wsMessage = new HashMap<>();
        wsMessage.put("deviceName", sim.getDeviceName());
        wsMessage.put("phoneNumber", sim.getPhoneNumber());
        wsMessage.put("comNumber", sim.getComName());
        wsMessage.put("customerId", s.getAccountId());
        wsMessage.put("serviceCode", resolvedServiceCode);
        wsMessage.put("countryName", s.getCountry().getCountryCode());
        wsMessage.put("smsContent", rec.body);
        wsMessage.put("fromNumber", rec.sender);
        wsMessage.put("otp", otp);

        StompSession stompSession = remoteStompClientConfig.getSession();
        if (stompSession != null && stompSession.isConnected()) {
            try {
                stompSession.send("/topic/receive-otp", wsMessage);
                log.info("📡 Sent WS /topic/receive-otp: {}", wsMessage);
            } catch (Exception e) {
                log.error("❌ Lỗi khi push WS OTP cho orderId={}: {}", s.getOrderId(), e.getMessage(), e);
            }
        } else {
            log.warn("⚠️ Không thể gửi WS OTP vì stompSession null hoặc chưa connect");
        }

        // 3) Nếu là buy.otp.service → chờ push xong rồi mới đóng session
        if (isBuyOtp) {
            log.info("⌛ Sẽ đóng session buy.otp.service sau 2s (orderId={})", s.getOrderId());
            scheduler.schedule(() -> {
                closeSession(sim, s);
            }, 2, TimeUnit.SECONDS);
        }
    }


    // === Process Call === (giữ nguyên logic cũ)
    public void processIncomingCall(Sim sim, String fromNumber, RentSession session) {
        if (!session.isActive() || session.isCallHandled()) return;

        log.info("📞 Incoming call từ {} đến SIM {}", fromNumber, sim.getPhoneNumber());
        session.setCallHandled(true);
        session.setCallStartTime(Instant.now());

        sendAtCommand(sim, "ATA");

        scheduler.schedule(() -> {
            String remoteRecordPath = null;
            try {
                log.info("⏹️ Kết thúc cuộc gọi sau 20s cho SIM {}", sim.getPhoneNumber());
                sendAtCommand(sim, "ATH");

                if (session.isRecord()) {
                    try {
                        remoteRecordPath = saveCallRecording(sim, session, fromNumber);
                        session.setRecordFilePath(remoteRecordPath);
                    } catch (Exception ex) {
                        log.error("❌ Lỗi ghi âm/upload orderId={}: {}", session.getOrderId(), ex.getMessage(), ex);
                    }
                }

                callRecordService.saveCallRecord(
                        session.getOrderId(), session.getAccountId(), sim, fromNumber,
                        "RECEIVED", remoteRecordPath, session.getCallStartTime(),
                        Instant.now(), session.getStartTime().plus(Duration.ofMinutes(session.getDurationMinutes()))
                );
                forwardCallResult(sim, session, fromNumber, "RECEIVED", remoteRecordPath);

            } catch (Exception e) {
                log.error("❌ Error khi kết thúc call: {}", e.getMessage(), e);
                callRecordService.saveCallRecord(
                        session.getOrderId(), session.getAccountId(), sim, fromNumber,
                        "ERROR", null, session.getCallStartTime(),
                        Instant.now(), session.getStartTime().plus(Duration.ofMinutes(session.getDurationMinutes()))
                );
                forwardCallResult(sim, session, fromNumber, "ERROR", null);
            } finally {
                closeSession(sim, session);
            }
        }, 20, TimeUnit.SECONDS);
    }

    private String saveCallRecording(Sim sim, RentSession session, String fromNumber) throws Exception {
        String baseDir = (localRecordingDir != null && !localRecordingDir.isBlank())
                ? localRecordingDir : System.getProperty("java.io.tmpdir");

        Files.createDirectories(Paths.get(baseDir));
        String filename = session.getOrderId() + "-" + System.currentTimeMillis() + ".wav";
        String localPath = Paths.get(baseDir, filename).toString();

        createFakeWavFile(localPath, 2);
        log.info("💾 Created recording at local path: {}", localPath);

        return uploadFileToRemote(localPath, "/home/record");
    }

    private String uploadFileToRemote(String localFilePath, String remoteDir) throws Exception {
        JSch jsch = new JSch();
        Session session = null;
        ChannelSftp sftp = null;
        try {
            if (sshKeyPath != null && !sshKeyPath.isBlank()) {
                if (sshKeyPassphrase != null && !sshKeyPassphrase.isBlank()) {
                    jsch.addIdentity(sshKeyPath, sshKeyPassphrase);
                } else {
                    jsch.addIdentity(sshKeyPath);
                }
            }
            session = jsch.getSession(sshUser, sshHost, sshPort);
            if (sshPassword != null && !sshPassword.isBlank()) session.setPassword(sshPassword);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect(10000);

            Channel channel = session.openChannel("sftp");
            channel.connect(5000);
            sftp = (ChannelSftp) channel;

            try { sftp.cd(remoteDir); } catch (SftpException e) { sftp.mkdir(remoteDir); sftp.cd(remoteDir); }

            File local = new File(localFilePath);
            String remotePath = remoteDir + "/" + local.getName();
            try (FileInputStream fis = new FileInputStream(local)) {
                sftp.put(fis, remotePath);
            }
            log.info("📤 Uploaded recording to VPS {}:{}", sshHost, remotePath);
            return remotePath;
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private void createFakeWavFile(String path, int durationSeconds) throws Exception {
        int sampleRate = 8000;
        short channels = 1;
        short bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int totalDataLen = durationSeconds * byteRate;

        try (FileOutputStream out = new FileOutputStream(path)) {
            writeString(out, "RIFF");
            writeInt(out, 36 + totalDataLen);
            writeString(out, "WAVE");
            writeString(out, "fmt ");
            writeInt(out, 16);
            writeShort(out, (short) 1);
            writeShort(out, channels);
            writeInt(out, sampleRate);
            writeInt(out, byteRate);
            writeShort(out, (short) (channels * bitsPerSample / 8));
            writeShort(out, bitsPerSample);
            writeString(out, "data");
            writeInt(out, totalDataLen);

            byte[] silence = new byte[1024];
            int bytesToWrite = totalDataLen;
            while (bytesToWrite > 0) {
                int chunk = Math.min(bytesToWrite, silence.length);
                out.write(silence, 0, chunk);
                bytesToWrite -= chunk;
            }
        }
    }
    private void writeInt(OutputStream os, int value) throws Exception {
        os.write(value & 0xff); os.write((value >> 8) & 0xff);
        os.write((value >> 16) & 0xff); os.write((value >> 24) & 0xff);
    }
    private void writeShort(OutputStream os, short value) throws Exception {
        os.write(value & 0xff); os.write((value >> 8) & 0xff);
    }
    private void writeString(OutputStream os, String s) throws Exception {
        os.write(s.getBytes());
    }

    private void forwardCallResult(Sim sim, RentSession s, String fromNumber, String status, String recordFile) {
        Map<String, Object> wsMessage = new HashMap<>();
        wsMessage.put("deviceName", sim.getDeviceName());
        wsMessage.put("phoneNumber", sim.getPhoneNumber());
        wsMessage.put("comNumber", sim.getComName());
        wsMessage.put("customerId", s.getAccountId());
        wsMessage.put("serviceCode", "CALL");
        wsMessage.put("countryName", s.getCountry().getCountryCode());
        wsMessage.put("fromNumber", fromNumber);
        wsMessage.put("status", status);
        if (recordFile != null) wsMessage.put("recordFile", recordFile);

        StompSession stompSession = remoteStompClientConfig.getSession();
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.send("/topic/receive-call", wsMessage);
            log.info("📡 Sent WS /topic/receive-call: {}", wsMessage);
        }
    }

    private void checkAndRefund(Sim sim, RentSession session) {
        if (session.isActive()) return;
        boolean hasOtp = smsMessageRepository.existsByOrderId(session.getOrderId());
        if (!hasOtp) {
            try {
                callUpdateRefundApi(session.getOrderId());
                log.info("🔄 Auto refund orderId={} (SIM={}, acc={}) vì hết hạn không nhận OTP",
                        session.getOrderId(), sim.getPhoneNumber(), session.getAccountId());
            } catch (Exception e) {
                log.error("❌ Error calling refund API for orderId={}", session.getOrderId(), e);
            }
        }
        stopWorkerIfNoActiveSession(sim);
    }

    private void callUpdateSuccessApi(String orderId) {
        String url = orderApiBaseUrl + "api/otp/order/" + orderId + "/success";
        restTemplate.postForEntity(url, null, Void.class);
    }
    private void callUpdateRefundApi(String orderId) {
        String url = orderApiBaseUrl + "api/otp/order/" + orderId + "/refund";
        restTemplate.postForEntity(url, null, Void.class);
    }

    private void startWorkerForSim(Sim sim) {
        workers.computeIfAbsent(sim.getComName(), com -> {
            PortWorker worker = new PortWorker(sim, 4000, this);
            new Thread(worker, "PortWorker-" + com).start();
            return worker;
        });
    }
    private void stopWorkerIfNoActiveSession(Sim sim) {
        List<RentSession> sessions = activeSessions.getOrDefault(sim.getId(), List.of());
        boolean hasActive = sessions.stream().anyMatch(RentSession::isActive);
        if (!hasActive) {
            PortWorker w = workers.remove(sim.getComName());
            if (w != null) {
                w.stop();
                log.info("🛑 Stop worker for SIM={} vì không còn session active", sim.getPhoneNumber());
            }
        }
    }

    private void sendAtCommand(Sim sim, String command) {
        PortWorker w = workers.get(sim.getComName());
        if (w != null) w.sendCommand(command);
    }

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

    private void closeSession(Sim sim, RentSession session) {
        List<RentSession> sessions = activeSessions.get(sim.getId());
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) activeSessions.remove(sim.getId());
        }
        stopWorkerIfNoActiveSession(sim);
        log.info("✅ Closed session for orderId={} on SIM={}", session.getOrderId(), sim.getPhoneNumber());
    }

    public List<RentSession> getActiveSessions(String simId) {
        return activeSessions.getOrDefault(simId, List.of());
    }

    @Data
    @AllArgsConstructor
    public static class RentSession {
        private Long accountId;
        private List<String> services;
        private Instant startTime;
        private int durationMinutes;
        private Country country;
        private String orderId;
        private OtpSessionType type;
        private boolean otpReceived;
        private String serviceType;
        private boolean record;
        private boolean callHandled;
        private Instant callStartTime;
        private String recordFilePath;
        boolean isActive() {
            return Instant.now().isBefore(startTime.plus(Duration.ofMinutes(durationMinutes)));
        }
    }
}
