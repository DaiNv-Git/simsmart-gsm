package app.simsmartgsm.uitils;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.service.GsmListenerService;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class PortWorker implements Runnable {

    private final Sim sim;
    private final long scanIntervalMs;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private final GsmListenerService listenerService;

    private SerialPort port;
    private AtCommandHelper helper;

    public PortWorker(Sim sim, long scanIntervalMs, GsmListenerService listenerService) {
        this.sim = sim;
        this.scanIntervalMs = scanIntervalMs;
        this.listenerService = listenerService;
    }

    public void stop() {
        running = false;
        closePort();
    }

    /** Đẩy task gửi SMS vào queue */
    public void sendSms(String to, String content) {
        queue.offer(new Task(TaskType.SEND, to, content));
    }

    /** Đẩy task quét SMS vào queue */
    public void forceScan() {
        queue.offer(new Task(TaskType.SCAN, null, null));
    }

    @Override
    public void run() {
        log.info("▶️ Starting PortWorker for {}", sim.getComName());

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
                log.error("❌ Worker error {}: {}", sim.getComName(), e.getMessage(), e);
                closePort();
                safeSleep(2000);
            }
        }

        closePort();
        log.info("⏹️ PortWorker for {} stopped", sim.getComName());
    }

    /** Đảm bảo port mở, nếu chưa thì mở lại */
    private boolean ensurePort() {
        try {
            if (port != null && port.isOpen()) return true;

            port = SerialPort.getCommPort(sim.getComName());
            port.setBaudRate(115200);
            port.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 3000, 3000
            );

            if (!port.openPort()) {
                log.warn("⚠️ Cannot open port {}", sim.getComName());
                return false;
            }

            helper = new AtCommandHelper(port);
            helper.setTextMode(true);
            helper.setCharset("GSM");
            log.info("✅ Opened port {}", sim.getComName());
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to init port {}: {}", sim.getComName(), e.getMessage());
            closePort();
            return false;
        }
    }

    /** Đóng port */
    private void closePort() {
        try { if (helper != null) helper.close(); } catch (Exception ignored) {}
        try { if (port != null && port.isOpen()) port.closePort(); } catch (Exception ignored) {}
        helper = null;
        port = null;
    }

    /** Gửi SMS */
    private void doSendSms(String to, String content) {
        try {
            boolean ok = helper.sendTextSms(to, content, Duration.ofSeconds(30));
            log.info("📤 SEND result on {} -> {} : {}", sim.getComName(), to, ok ? "✅ OK" : "❌ FAIL");
        } catch (Exception e) {
            log.error("❌ SEND error on {}: {}", sim.getComName(), e.getMessage());
            closePort();
        }
    }

    /** Quét SMS mới */
    private void doScanSms() {
        try {
            var smsList = helper.listUnreadSmsText(5000);
            if (smsList.isEmpty()) {
                log.debug("📭 {} no unread SMS", sim.getComName());
                return;
            }

            for (var rec : smsList) {
                log.info("📩 {} got SMS from {}: {}", sim.getComName(), rec.sender, rec.body);

                // Forward SMS về listener service để xử lý OTP
                try {
                    listenerService.processSms(sim, rec);
                } catch (Exception e) {
                    log.error("❌ Error processing SMS {} on {}: {}", rec, sim.getComName(), e.getMessage(), e);
                }

                // Xóa SMS khỏi modem sau khi xử lý
                if (rec.index != null) {
                    try {
                        boolean deleted = helper.deleteSms(rec.index);
                        if (deleted) {
                            log.info("🗑️ Deleted SMS index={} from {}", rec.index, sim.getComName());
                        } else {
                            log.warn("⚠️ Failed to delete SMS index={} from {}", rec.index, sim.getComName());
                        }
                    } catch (Exception e) {
                        log.error("❌ Error deleting SMS index={} on {}: {}", rec.index, sim.getComName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ SCAN error {}: {}", sim.getComName(), e.getMessage(), e);
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
