package app.simsmartgsm.service;

import app.simsmartgsm.entity.SmsMessage;
import com.fazecast.jSerialComm.SerialPort;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SmsSenderService (async + per-port queue).
 *
 * Usage:
 *   CompletableFuture<SmsMessage> f = smsSenderService.sendSmsAsync("COM71", "0709...", "hi");
 *   f.whenComplete((msg, ex) -> { ... });
 */
@Service
@Slf4j
public class SmsSenderService {

    private static final int MAX_RETRY = 3;

    // worker threads pool (for running per-port workers)
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setName("sms-worker-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    // map portName -> queue of tasks
    private final ConcurrentHashMap<String, LinkedBlockingQueue<SendTask>> portQueues = new ConcurrentHashMap<>();

    // map portName -> worker running flag
    private final ConcurrentHashMap<String, AtomicBoolean> portWorkerRunning = new ConcurrentHashMap<>();

    /**
     * Public async API: trả về CompletableFuture ngay, task được queue và xử lý tuần tự trên port tương ứng.
     */
    public CompletableFuture<SmsMessage> sendSmsAsync(String portName, String toNumber, String message) {
        CompletableFuture<SmsMessage> future = new CompletableFuture<>();
        SendTask task = new SendTask(portName, toNumber, message, future);

        // lấy queue cho port, tạo nếu chưa có
        LinkedBlockingQueue<SendTask> q = portQueues.computeIfAbsent(portName, k -> new LinkedBlockingQueue<>());
        q.offer(task);

        // ensure worker is running for this port
        startWorkerIfNeeded(portName, q);
        return future;
    }

    /**
     * Khởi động worker cho port nếu chưa chạy
     */
    private void startWorkerIfNeeded(String portName, LinkedBlockingQueue<SendTask> queue) {
        AtomicBoolean running = portWorkerRunning.computeIfAbsent(portName, k -> new AtomicBoolean(false));
        if (running.compareAndSet(false, true)) {
            workerExecutor.submit(() -> {
                log.info("Worker started for port {}", portName);
                try {
                    while (true) {
                        // poll with timeout để worker có thể tự stop nếu queue rỗng trong 60s
                        SendTask task = queue.poll(60, TimeUnit.SECONDS);
                        if (task == null) {
                            // no task for a while => stop worker to free resources
                            log.info("No tasks for port {} for 60s, stopping worker", portName);
                            break;
                        }
                        try {
                            // thực hiện gửi (blocking)
                            SmsMessage res = sendOne(portName, task.toNumber, task.text);
                            task.future.complete(res);
                        } catch (Exception e) {
                            log.error("Error while sending SMS on {}: {}", portName, e.getMessage(), e);
                            task.future.completeExceptionally(e);
                        }
                    }
                } catch (InterruptedException ie) {
                    log.warn("Worker for {} interrupted", portName);
                    Thread.currentThread().interrupt();
                } finally {
                    running.set(false);
                    // nếu queue vẫn có task mới sau khi set false, ensure restart
                    if (!queue.isEmpty()) {
                        startWorkerIfNeeded(portName, queue);
                    }
                    log.info("Worker stopped for port {}", portName);
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SmsSenderService workerExecutor");
        workerExecutor.shutdownNow();
    }

    // -----------------------
    // sendOne (blocking) - bạn đã có, mình giữ gần nguyên bản
    // -----------------------
    public SmsMessage sendOne(String portName, String phoneNumber, String text) {
        String status = "FAIL";
        StringBuilder resp = new StringBuilder();
        String fromPhone = "unknown";

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            SerialPort port = null;
            try {
                port = openPort(portName);

                try (OutputStream out = port.getOutputStream();
                     InputStream in = port.getInputStream()) {

                    Scanner sc = new Scanner(in, StandardCharsets.US_ASCII);

                    // === clear buffer trước khi bắt đầu ===
                    while (in.available() > 0) in.read();

                    // === lấy số SIM gửi ===
                    fromPhone = getSimPhoneNumber(out, sc);

                    // === config cơ bản ===
                    sendCmd(out, "AT");
                    sendCmd(out, "AT+CMGF=1");          // text mode
                    sendCmd(out, "AT+CSCS=\"GSM\"");    // charset
                    sendCmd(out, "AT+CNMI=2,1,0,1,0");  // enable push DLR
                    sendCmd(out, "AT+CMGD=1,4");        // xoá toàn bộ inbox, tránh đầy bộ nhớ

                    // === chuẩn bị gửi SMS ===
                    sendCmd(out, "AT+CMGS=\"" + phoneNumber + "\"");

                    // === chờ dấu '>' ===
                    String prompt = waitForPrompt(in, 10000); // tăng timeout lên 10s cho chắc
                    if (prompt == null) {
                        log.warn("❌ {}: không nhận được dấu '>'", portName);
                        status = "FAIL";
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                        continue;
                    }

                    // === gửi nội dung SMS ===
                    out.write((text + "\r").getBytes(StandardCharsets.UTF_8));
                    out.write(0x1A); // Ctrl+Z
                    out.flush();

                    // === đọc phản hồi ===
                    String modemResp = readResponse(in, 30000, resp);
                    if (modemResp.contains("OK") || modemResp.contains("+CDS:")) {
                        status = "OK";
                    } else if (modemResp.contains("+CMGS")) {
                        status = "SENT";
                    } else {
                        status = "FAIL";
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ Lỗi gửi SMS lần {}/{} qua {} -> {}: {}", attempt, MAX_RETRY, portName, phoneNumber, e.getMessage());
            } finally {
                if (port != null && port.isOpen()) port.closePort();
            }

            if ("OK".equals(status) || "SENT".equals(status)) break;
            try {
                Thread.sleep((long) Math.pow(2, attempt) * 1000);
            } catch (InterruptedException ignored) {}
        }

        return SmsMessage.builder()
                .fromPort(portName)
                .fromPhone(fromPhone)
                .toPhone(phoneNumber)
                .message(text)
                .modemResponse(resp.toString())
                .type(status)
                .timestamp(Instant.now())
                .build();
    }

    // ---------- helper (giữ nguyên / có thể tùy chỉnh) ----------
    private String waitForPrompt(InputStream in, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (in.available() > 0) {
                int b = in.read();
                if (b == '>') return ">";
            }
            Thread.sleep(50);
        }
        return null;
    }

    private String readResponse(InputStream in, long timeoutMs, StringBuilder resp) throws Exception {
        long start = System.currentTimeMillis();
        StringBuilder lineBuffer = new StringBuilder();

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (in.available() > 0) {
                int b = in.read();
                if (b == -1) break;

                char c = (char) b;
                if (c == '\r' || c == '\n') {
                    String line = lineBuffer.toString().trim();
                    if (!line.isEmpty()) {
                        resp.append(line).append("\n");
                        log.debug("📥 modem resp: {}", line);
                    }
                    lineBuffer.setLength(0);
                } else {
                    lineBuffer.append(c);
                }
            }
            Thread.sleep(20);
        }
        return resp.toString();
    }

    private SerialPort openPort(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        // set timeout non-blocking small read, because we control blocking in readResponse
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);
        if (!port.openPort()) throw new RuntimeException("Không mở được port " + portName);
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        String fullCmd = cmd + "\r";
        out.write(fullCmd.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        log.debug("➡️ CMD: {}", cmd);
        Thread.sleep(200); // small delay tránh modem bị nghẽn
    }

    private String getSimPhoneNumber(OutputStream out, Scanner sc) throws Exception {
        sendCmd(out, "AT+CNUM");
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 2000) {
            if (sc.hasNextLine()) {
                String line = sc.nextLine();
                if (line.contains("+CNUM")) {
                    String[] parts = line.split(",");
                    if (parts.length > 1) {
                        return parts[1].replaceAll("\"", "").trim();
                    }
                }
            }
        }
        return "unknown";
    }

    // ---------- internal task ----------
    private static class SendTask {
        final String portName;
        final String toNumber;
        final String text;
        final CompletableFuture<SmsMessage> future;

        SendTask(String portName, String toNumber, String text, CompletableFuture<SmsMessage> future) {
            this.portName = portName;
            this.toNumber = toNumber;
            this.text = text;
            this.future = future;
        }
    }
}
