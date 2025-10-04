package app.simsmartgsm.scheduled;

import app.simsmartgsm.config.RemoteStompClientConfig;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsJobDispatcher {

    private final RemoteStompClientConfig stompClientConfig;
    private String localDeviceName;

    // Map COM → queue job
    private final Map<String, BlockingQueue<JSONObject>> comQueues = new ConcurrentHashMap<>();
    private final Map<String, Thread> comWorkers = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            this.localDeviceName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            this.localDeviceName = "UNKNOWN_HOST";
            log.error("❌ Không lấy được hostname", e);
        }
        log.info("💻 Local deviceName: {}", localDeviceName);

        new Thread(this::subscribeSmsJobs).start();
    }

    private void subscribeSmsJobs() {
        while (true) {
            try {
                StompSession session = stompClientConfig.getSession();
                if (session != null && session.isConnected()) {
                    session.subscribe("/topic/sms-job-topic", new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return byte[].class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            try {
                                String body = new String((byte[]) payload);
                                JSONObject job = new JSONObject(body);

                                if (!localDeviceName.equalsIgnoreCase(job.optString("deviceName"))) {
                                    log.info("🚫 Bỏ qua job không khớp deviceName");
                                    return;
                                }

                                String comName = job.getString("comName");
                                // put vào queue theo COM
                                comQueues.computeIfAbsent(comName, k -> {
                                    BlockingQueue<JSONObject> q = new LinkedBlockingQueue<>();
                                    startComWorker(comName, q, session);
                                    return q;
                                }).offer(job);

                            } catch (Exception e) {
                                log.error("❌ Lỗi khi parse job", e);
                            }
                        }
                    });
                    log.info("✅ Subscribed to /topic/sms-job-topic");
                    break;
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("❌ Lỗi subscribe SMS job", e);
            }
        }
    }

    /** Worker: mỗi COM có 1 thread riêng */
    private void startComWorker(String comName, BlockingQueue<JSONObject> queue, StompSession session) {
        Thread worker = new Thread(() -> {
            while (true) {
                try {
                    JSONObject job = queue.take(); // chờ job
                    processSmsJob(job, comName, session);
                } catch (Exception e) {
                    log.error("❌ Lỗi worker COM {}", comName, e);
                }
            }
        }, "Worker-" + comName);
        worker.start();
        comWorkers.put(comName, worker);
        log.info("✅ Started worker for COM {}", comName);
    }

    /** Xử lý job + retry 3 lần */
    private void processSmsJob(JSONObject job, String comName, StompSession session) {
        String localMsgId = job.optString("localMsgId", "");
        String simId = job.optString("simId", "");
        String phoneNumber = job.optString("phoneNumber", "");
        String content = job.optString("content", "");
        String campaignId = job.optString("campaignId", null);
        String sessionId = job.optString("sessionId", null);

        JSONObject response = new JSONObject();
        response.put("localMsgId", localMsgId);
        response.put("simId", simId);
        response.put("campaignId", campaignId);
        response.put("sessionId", sessionId);
        response.put("phoneNumber", phoneNumber);

        boolean success = false;
        String errorMsg = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                log.info("📤 Gửi SMS attempt {} qua {} tới {}: {}", attempt, comName, phoneNumber, content);
                success = sendSmsViaCom(comName, phoneNumber, content);
                if (success) break;
                Thread.sleep(2000); // delay giữa các lần retry
            } catch (Exception e) {
                errorMsg = e.getMessage();
                log.warn("⚠️ Attempt {} fail: {}", attempt, errorMsg);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }

        if (success) {
            response.put("status", "SENT");
            response.put("errorMsg", JSONObject.NULL);
        } else {
            response.put("status", "FAILED");
            response.put("errorMsg", errorMsg != null ? errorMsg : "Unknown error");
        }

        session.send("/app/sms-response", response.toString().getBytes());
        log.info("📩 Kết quả gửi: {}", response);
    }

    /** Hàm giả lập gửi SMS bằng AT command */
    private boolean sendSmsViaCom(String comName, String phoneNumber, String content) throws Exception {
        SerialPort port = SerialPort.getCommPort(comName);
        port.setBaudRate(115200);

        if (!port.openPort()) {
            throw new RuntimeException("Không mở được cổng COM: " + comName);
        }

        try (OutputStream out = port.getOutputStream();
             InputStream in = port.getInputStream()) {

            out.write("AT+CMGF=1\r".getBytes());
            out.flush();
            Thread.sleep(500);

            out.write(("AT+CMGS=\"" + phoneNumber + "\"\r").getBytes());
            out.flush();
            Thread.sleep(500);

            out.write(content.getBytes());

            // 4. Gửi Ctrl+Z (ASCII 26) để kết thúc
            out.write(26);
            out.flush();

            // 5. Chờ phản hồi từ modem
            Thread.sleep(3000);
            byte[] buffer = new byte[1024];
            int len = in.read(buffer);
            String resp = new String(buffer, 0, len);

            log.info("📩 Response from {}: {}", comName, resp);

            return resp.contains("OK");
        } finally {
            port.closePort();
        }
    }

}
