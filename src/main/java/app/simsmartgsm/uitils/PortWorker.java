package app.simsmartgsm.uitils;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.service.GsmListenerService;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
        log.info("▶️ Start worker for SIM {} (COM={})", sim.getPhoneNumber(), sim.getComName());
        try (AtCommandHelper helper = AtCommandHelper.open(
                sim.getComName(), 115200, 4000, 2000)) {

            helper.echoOff();
            helper.setTextMode(true);
            helper.setNewMessageIndicationDefault();
            log.info("🔄 Worker loop started for {}", sim.getComName());

            while (running) {
                try {
                    log.debug("🔍 Scanning for unread SMS on {}", sim.getComName());
                    System.out.println("🔍 SCAN SMS on " + sim.getComName());

                    // 1. Lấy SMS chưa đọc
                    List<AtCommandHelper.SmsRecord> unread = helper.listUnreadSmsText(1000);

                    for (AtCommandHelper.SmsRecord rec : unread) {
                        log.info("📩 New SMS on {}: {}", sim.getComName(), rec);

                        // 2. Gửi về GsmListenerService xử lý
                        listenerService.processSms(sim, rec);

                        // 3. Xoá SMS để không đọc lại
                        try {
                            boolean deleted = helper.deleteSms(rec.index);
                            if (deleted) {
                                log.info("🗑 Deleted SMS index {} on {}", rec.index, sim.getComName());
                            } else {
                                log.warn("⚠️ Failed to delete SMS index {} on {}", rec.index, sim.getComName());
                            }
                        } catch (Exception e) {
                            log.warn("⚠️ Error deleting SMS index {}: {}", rec.index, e.getMessage());
                        }
                    }

                    // 4. Nghỉ theo chu kỳ scan
                    Thread.sleep(scanIntervalMs);

                } catch (Exception e) {
                    log.error("❌ Error scanning SIM {}: {}", sim.getComName(), e.getMessage(), e);
                    Thread.sleep(2000); // nghỉ 2s rồi thử lại
                }
            }
        } catch (Exception e) {
            log.error("❌ Cannot init PortWorker for {}: {}", sim.getComName(), e.getMessage(), e);
        } finally {
            log.info("⏹ Worker stopped for SIM {} (COM={})", sim.getPhoneNumber(), sim.getComName());
        }
    }
    /** Đảm bảo port mở, nếu chưa thì mở lại */
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
            helper.setNewMessageIndicationDefault(); // ✅ thêm dòng này để modem báo SMS mới
            startUrcListener();

            log.info("✅ Opened port {}", sim.getComName());
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to init port {}: {}", sim.getComName(), e.getMessage());
            closePort();
            return false;
        }
    }

    private void startUrcListener() {
        new Thread(() -> {
            try {
                var in = port.getInputStream();
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[128];
                while (running && port.isOpen()) {
                    int n = in.read(buf);
                    if (n > 0) {
                        String chunk = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                        sb.append(chunk);
                        String all = sb.toString();
                        if (all.contains("+CMTI:")) {
                            log.info("📨 URC báo có SMS mới trên {}", sim.getComName());
                            forceScan(); // quét ngay
                            sb.setLength(0); // reset buffer
                        }
                    }
                }
            } catch (Exception e) {
                log.error("❌ URC listener error: {}", e.getMessage());
            }
        }, "URC-" + sim.getComName()).start();
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

            forceScan();

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
