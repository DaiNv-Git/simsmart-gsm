package app.simsmartgsm.uitils;

import app.simsmartgsm.service.GsmListenerService;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class PortWorker implements Runnable {

    private final String comName;
    private final long scanIntervalMs;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private final GsmListenerService listenerService;

    private SerialPort port;
    private AtCommandHelper helper;

    public PortWorker(String comName, long scanIntervalMs, GsmListenerService listenerService) {
        this.comName = comName;
        this.scanIntervalMs = scanIntervalMs;
        this.listenerService = listenerService;
    }

    public void stop() {
        running = false;
        closePort();
    }

    public void sendSms(String to, String content) {
        queue.offer(new Task(TaskType.SEND, to, content));
    }

    public void forceScan() {
        queue.offer(new Task(TaskType.SCAN, null, null));
    }

    @Override
    public void run() {
        log.info("▶️ Starting PortWorker for {}", comName);

        while (running) {
            try {
                if (!ensurePort()) {
                    Thread.sleep(2000);
                    continue;
                }

                // Ưu tiên task từ queue
                Task task = queue.poll();
                if (task != null) {
                    if (task.type == TaskType.SEND) {
                        doSendSms(task.to, task.content);
                    } else if (task.type == TaskType.SCAN) {
                        doScanSms();
                    }
                } else {
                    // Không có task → scan định kỳ
                    doScanSms();
                    Thread.sleep(scanIntervalMs);
                }

            } catch (Exception e) {
                log.error("❌ Worker error {}: {}", comName, e.getMessage(), e);
                closePort();
                safeSleep(2000);
            }
        }

        closePort();
        log.info("⏹️ PortWorker for {} stopped", comName);
    }

    private boolean ensurePort() {
        try {
            if (port != null && port.isOpen()) return true;

            port = SerialPort.getCommPort(comName);
            port.setBaudRate(115200);
            port.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 3000, 3000
            );

            if (!port.openPort()) {
                log.warn("⚠️ Cannot open port {}", comName);
                return false;
            }

            helper = new AtCommandHelper(port);
            helper.setTextMode(true);
            helper.setCharset("GSM");
            log.info("✅ Opened port {}", comName);
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to init port {}: {}", comName, e.getMessage());
            closePort();
            return false;
        }
    }

    private void closePort() {
        try { if (helper != null) helper.close(); } catch (Exception ignored) {}
        try { if (port != null && port.isOpen()) port.closePort(); } catch (Exception ignored) {}
        helper = null;
        port = null;
    }

    private void doSendSms(String to, String content) {
        try {
            boolean ok = helper.sendTextSms(to, content, Duration.ofSeconds(30));
            log.info("📤 SEND result on {} -> {} : {}", comName, to, ok ? "✅ OK" : "❌ FAIL");
        } catch (Exception e) {
            log.error("❌ SEND error on {}: {}", comName, e.getMessage());
            closePort();
        }
    }

    private void doScanSms() {
        try {
            var smsList = helper.listUnreadSmsText(5000);
            for (var rec : smsList) {
                log.info("📩 {} got SMS from {}: {}", comName, rec.sender, rec.body);
                if (rec.index != null) {
                    helper.deleteSms(rec.index); // xoá để tránh trùng
                }
                // TODO: forward SMS về broker hoặc DB
            }
        } catch (Exception e) {
            log.error("❌ SCAN error {}: {}", comName, e.getMessage());
            closePort();
        }
    }

    private void safeSleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // --- Task DTO ---
    enum TaskType { SEND, SCAN }

    static class Task {
        TaskType type;
        String to;
        String content;
        Task(TaskType type, String to, String content) {
            this.type = type;
            this.to = to;
            this.content = content;
        }
    }
}
