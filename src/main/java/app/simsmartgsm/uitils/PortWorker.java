package app.simsmartgsm.uitils;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.service.GsmListenerService;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
public class PortWorker implements Runnable {

    private final Sim sim;
    private final long scanIntervalMs;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private final GsmListenerService listenerService;

    private SerialPort port;
    private AtCommandHelper helper;

    // ✅ thêm scheduler cho retry scan
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public PortWorker(Sim sim, long scanIntervalMs, GsmListenerService listenerService) {
        this.sim = sim;
        this.scanIntervalMs = scanIntervalMs;
        this.listenerService = listenerService;
    }

    public void stop() {
        running = false;
        closePort();
        scheduler.shutdownNow();
    }

    /** Đẩy task gửi SMS vào queue */
    public void sendSms(String to, String content) {
        queue.offer(new Task(TaskType.SEND, to, content));
    }

    /** Đẩy task quét SMS vào queue */
    public void forceScan() {
        queue.offer(new Task(TaskType.SCAN, null, null));
    }

    /** Gửi AT command trực tiếp */
    public void sendCommand(String cmd) {
        if (port != null && port.isOpen()) {
            try {
                String at = cmd + "\r\n";
                port.getOutputStream().write(at.getBytes(StandardCharsets.US_ASCII));
                port.getOutputStream().flush();
                log.info("➡️ Sent AT command to {}: {}", sim.getComName(), cmd);
            } catch (IOException e) {
                log.error("❌ Error sending AT command {}: {}", cmd, e.getMessage());
            }
        }
    }

    @Override
    public void run() {
        log.info("▶️ Start worker for SIM {} (COM={})", sim.getPhoneNumber(), sim.getComName());
        while (running) {
            try {
                if (!ensurePort()) {
                    safeSleep(2000);
                    continue;
                }

                Task task = queue.poll();
                if (task != null) {
                    if (task.type == TaskType.SEND) {
                        doSendSms(task.to, task.content);
                    } else if (task.type == TaskType.SCAN) {
                        doScanSms();
                    }
                } else {
                    doScanSms(); // quét định kỳ
                    safeSleep(scanIntervalMs);
                }

            } catch (Exception e) {
                log.error("❌ Worker error on {}: {}", sim.getComName(), e.getMessage(), e);
                closePort();
                safeSleep(2000);
            }
        }
        closePort();
        log.info("⏹ Worker stopped for {}", sim.getComName());
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
            helper.setNewMessageIndicationDefault(); // CNMI
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
                byte[] buf = new byte[256];
                StringBuilder sb = new StringBuilder();
                while (running && port.isOpen()) {
                    int n = in.read(buf);
                    if (n > 0) {
                        String chunk = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                        sb.append(chunk);

                        String all = sb.toString();
                        if (all.contains("\r\n")) {
                            String[] lines = all.split("\r\n");
                            for (String line : lines) {
                                line = line.trim();
                                if (line.isEmpty()) continue;

                                if (line.contains("+CMTI:")) {
                                    log.info("📨 URC báo có SMS mới trên {}", sim.getComName());
                                    forceScan();
                                } else if (line.startsWith("RING")) {
                                    log.info("📞 RING on {}", sim.getComName());
                                } else if (line.startsWith("+CLIP")) {
                                    String from = parseCaller(line);
                                    log.info("📞 CLIP từ {} trên {}", from, sim.getComName());

                                    // Lấy session active
                                    List<GsmListenerService.RentSession> sessions =
                                            listenerService.getActiveSessions(sim.getId());
                                    if (!sessions.isEmpty()) {
                                        listenerService.processIncomingCall(sim, from, sessions.get(0));
                                    }
                                }
                            }
                            sb.setLength(0);
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    log.error("❌ URC listener error on {}: {}", sim.getComName(), e.getMessage());
                }
            }
        }, "URC-" + sim.getComName()).start();
    }

    private String parseCaller(String line) {
        try {
            int firstQuote = line.indexOf('"');
            int secondQuote = line.indexOf('"', firstQuote + 1);
            if (firstQuote >= 0 && secondQuote > firstQuote) {
                return line.substring(firstQuote + 1, secondQuote);
            }
        } catch (Exception e) {
            log.error("❌ Parse caller error: {}", e.getMessage());
        }
        return "UNKNOWN";
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

            if (ok) {
                // ✅ retry scan nhiều lần để không miss SMS đến muộn
                for (int i = 1; i <= 3; i++) {
                    int delay = i * 2;
                    scheduler.schedule(this::forceScan, delay, TimeUnit.SECONDS);
                }
            }
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

                try {
                    listenerService.processSms(sim, rec);
                } catch (Exception e) {
                    log.error("❌ Error processing SMS {} on {}: {}", rec, sim.getComName(), e.getMessage(), e);
                }

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
            log.error("❌ SCAN error {}: {}", sim.getComName(), e.getMessage());
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
