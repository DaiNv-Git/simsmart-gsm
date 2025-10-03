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
import com.jcraft.jsch.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.*;
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
    private final CallRecordService callRecordService;   // ✅ dùng service thay vì repo trực tiếp

    private final Map<String, PortWorker> workers = new ConcurrentHashMap<>();
    private final Map<String, List<RentSession>> activeSessions = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate = new RestTemplate();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Value("${gsm.test-mode:false}")
    private boolean testMode;

    @Value("${gsm.test-call-mode:false}")
    private boolean testCallMode;

    @Value("${gsm.loop-test-sms:false}")
    private boolean loopTestSms;

    @Value("${gsm.loop-test-sms-interval:30}")
    private int loopTestSmsInterval;

    @Value("${gsm.order-api.base-url}")
    private String orderApiBaseUrl;

    // SSH config
    @Value("${gsm.ssh.host}")
    private String sshHost;
    @Value("${gsm.ssh.port:22}")
    private int sshPort;
    @Value("${gsm.ssh.user}")
    private String sshUser;
    @Value("${gsm.ssh.password:}")
    private String sshPassword;
    @Value("${gsm.ssh.key-path:}")
    private String sshKeyPath;
    @Value("${gsm.ssh.key-passphrase:}")
    private String sshKeyPassphrase;

    @Value("${gsm.recording.local-temp:/tmp/recordings}")
    private String localRecordingDir;

    // === Rent SIM (SMS + CALL) ===
    public void rentSim(Sim sim, Long accountId, List<String> services,
                        int durationMinutes, Country country, String orderId, String type, Boolean record) {

        RentSession session = new RentSession(accountId, services, Instant.now(), durationMinutes,
                country, orderId, OtpSessionType.fromString(type),
                false, type, record, false, null, null);

        activeSessions.computeIfAbsent(sim.getId(), k -> new CopyOnWriteArrayList<>()).add(session);

        log.info("➕ Rent SIM {} by acc={} services={} duration={}m",
                sim.getPhoneNumber(), accountId, services, durationMinutes);

        startWorkerForSim(sim);

        // Schedule auto refund khi hết hạn
        scheduler.schedule(() -> checkAndRefund(sim, session), durationMinutes, TimeUnit.MINUTES);

        // --- TEST MODE ---
        if (!services.isEmpty()) {
            String service = services.get(0);
            if (testMode) {
                sendFakeSms(sim, service);
                if (loopTestSms) {
                    scheduler.scheduleAtFixedRate(() -> sendFakeSms(sim, service),
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
        String fakeSms = service.toUpperCase() + " OTP " + otp;

        AtCommandHelper.SmsRecord rec = new AtCommandHelper.SmsRecord();
        rec.sender = "TEST-SENDER";
        rec.body = fakeSms;

        log.info("📩 [TEST MODE] Fake incoming SMS: {}", rec.body);
        processSms(sim, rec);
    }

    // === Fake CALL ===
    private void sendFakeCall(Sim sim, RentSession session) {
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                log.info("📞 [TEST CALL MODE] Fake incoming call tới {}", sim.getPhoneNumber());
                processIncomingCall(sim, "FAKE-NUMBER", session);
            } catch (Exception e) {
                log.error("❌ Error fake call: {}", e.getMessage(), e);
            }
        }).start();
    }

    // === Process SMS (OTP) ===
    public void processSms(Sim sim, AtCommandHelper.SmsRecord rec) {
        List<RentSession> sessions = new ArrayList<>(activeSessions.getOrDefault(sim.getId(), List.of()));
        if (sessions.isEmpty()) return;

        String smsNorm = normalize(rec.body);
        String otp = extractOtp(rec.body);
        if (otp == null) return;

        boolean matched = false;
        for (RentSession s : sessions) {
            if (!s.isActive()) continue;
            for (String service : s.getServices()) {
                String serviceNorm = normalize(service);
                String servicePrefix = serviceNorm.substring(0, Math.min(4, serviceNorm.length()));
                if (smsNorm.startsWith(servicePrefix)) {
                    handleOtpReceived(sim, s, service, rec, otp);
                    matched = true;
                }
            }
        }
        if (!matched) {
            RentSession first = sessions.stream().filter(RentSession::isActive).findFirst().orElse(null);
            if (first != null) {
                handleOtpReceived(sim, first,
                        first.getServices().isEmpty() ? "UNKNOWN" : first.getServices().get(0), rec, otp);
            }
        }
    }

    // === OTP received ===
    private void handleOtpReceived(Sim sim, RentSession s, String service,
                                   AtCommandHelper.SmsRecord rec, String otp) {

        // ✅ Xác định loại dịch vụ
        boolean isBuyOtp = "buy.otp.service".equalsIgnoreCase(s.getServiceType());

        // ✅ Luôn lưu SMS vào DB
        String resolvedServiceCode = serviceRepository.findByCode(service)
                .map(svc -> svc.getCode()).orElse(service);

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
                .serviceType(s.getServiceType())
                .timestamp(Instant.now())
                .build();
        smsMessageRepository.save(sms);

        log.info("💾 Saved OTP orderId={} sim={} otp={} serviceType={}",
                s.getOrderId(), sim.getPhoneNumber(), otp, s.getServiceType());

        // ✅ buy.otp.service → chỉ notify 1 lần và kết thúc
        if (isBuyOtp) {
            if (!s.isOtpReceived()) {
                try {
                    callUpdateSuccessApi(s.getOrderId());
                    s.setOtpReceived(true);
                    // Kết thúc session ngay sau khi nhận OTP đầu tiên
                    closeSession(sim, s);
                } catch (Exception e) {
                    log.error("❌ Error update success API orderId={}", s.getOrderId(), e);
                }
            }
        } else {
            // ✅ rent.otp.service → có thể lưu nhiều lần, chỉ notify success lần đầu
            if (!s.isOtpReceived()) {
                try {
                    callUpdateSuccessApi(s.getOrderId());
                    s.setOtpReceived(true);
                } catch (Exception e) {
                    log.error("❌ Error update success API orderId={}", s.getOrderId(), e);
                }
            }
        }

        // ✅ Đẩy WS cho mọi OTP
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
            stompSession.send("/topic/receive-otp", wsMessage);
        }
    }


    // === Process Call ===
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

                // === Lưu CallRecord vào DB ===
                callRecordService.saveCallRecord(
                        session.getOrderId(),
                        session.getAccountId(),
                        sim,
                        fromNumber,
                        "RECEIVED",
                        remoteRecordPath,
                        session.getCallStartTime(),
                        Instant.now(), // callEndTime
                        session.getStartTime().plus(Duration.ofMinutes(session.getDurationMinutes())) // expireAt
                );

                forwardCallResult(sim, session, fromNumber, "RECEIVED", remoteRecordPath);

            } catch (Exception e) {
                log.error("❌ Error khi kết thúc call: {}", e.getMessage(), e);

                callRecordService.saveCallRecord(
                        session.getOrderId(),
                        session.getAccountId(),
                        sim,
                        fromNumber,
                        "ERROR",
                        null,
                        session.getCallStartTime(),
                        Instant.now(),
                        session.getStartTime().plus(Duration.ofMinutes(session.getDurationMinutes()))
                );

                forwardCallResult(sim, session, fromNumber, "ERROR", null);
            } finally {
                closeSession(sim, session);
            }
        }, 20, TimeUnit.SECONDS);
    }

    // === Save Recording ===
    private String saveCallRecording(Sim sim, RentSession session, String fromNumber) throws Exception {
        String baseDir = (localRecordingDir != null && !localRecordingDir.isBlank())
                ? localRecordingDir
                : System.getProperty("java.io.tmpdir");

        Files.createDirectories(Paths.get(baseDir));

        String filename = session.getOrderId() + "-" + System.currentTimeMillis() + ".wav";
        String localPath = Paths.get(baseDir, filename).toString();

        createFakeWavFile(localPath, 2);
        log.info("💾 Created recording at local path: {}", localPath);

        String remotePath = uploadFileToRemote(localPath, "/home/record");
        log.info("📤 Uploaded recording to VPS {}:{}", sshHost, remotePath);

        return remotePath;
    }

    // === Upload File ===
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
            if (sshPassword != null && !sshPassword.isBlank()) {
                session.setPassword(sshPassword);
            }
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
            return remotePath;
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    // === Fake WAV file ===
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
        os.write(value & 0xff);
        os.write((value >> 8) & 0xff);
        os.write((value >> 16) & 0xff);
        os.write((value >> 24) & 0xff);
    }
    private void writeShort(OutputStream os, short value) throws Exception {
        os.write(value & 0xff);
        os.write((value >> 8) & 0xff);
    }
    private void writeString(OutputStream os, String s) throws Exception {
        os.write(s.getBytes());
    }

    // === Forward Call result ===
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

    // === Refund check ===
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

    // === Call API update ===
    private void callUpdateSuccessApi(String orderId) {
        String url = orderApiBaseUrl + "api/otp/order/" + orderId + "/success";
        restTemplate.postForEntity(url, null, Void.class);
    }
    private void callUpdateRefundApi(String orderId) {
        String url = orderApiBaseUrl + "api/otp/order/" + orderId + "/refund";
        restTemplate.postForEntity(url, null, Void.class);
    }

    // === Worker ===
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

    // === AT command ===
    private void sendAtCommand(Sim sim, String command) {
        PortWorker w = workers.get(sim.getComName());
        if (w != null) {
            w.sendCommand(command);
        }
    }

    // === Utils ===
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

    // === Đóng session sau khi call kết thúc ===
    private void closeSession(Sim sim, RentSession session) {
        List<RentSession> sessions = activeSessions.get(sim.getId());
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                activeSessions.remove(sim.getId());
            }
        }
        stopWorkerIfNoActiveSession(sim);
        log.info("✅ Closed session for orderId={} on SIM={}", session.getOrderId(), sim.getPhoneNumber());
    }

    // === Lấy danh sách session còn hiệu lực theo SIM ===
    public List<RentSession> getActiveSessions(String simId) {
        return activeSessions.getOrDefault(simId, List.of());
    }

    // === RentSession ===
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
