package app.simsmartgsm.service;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimSyncService {

    private final SimRepository simRepository;
    private final PortManager portManager;
    // ==== CONFIG ====
    private static final int THREAD_POOL_SIZE = 8;          // scan song song
    private static final int AT_TIMEOUT_MS = 1000;
    private static final int OPEN_READ_TIMEOUT_MS = 2000;
    private static final int MISS_THRESHOLD = 3;            // số lần miss trước khi replaced/unhealthy
    private static final long SCAN_TIMEOUT_MIN = 1;         // await scan thread pool
    private static final long MONITOR_LOCK_TIMEOUT_MS = 200;// thời gian chờ lấy khóa port khi monitor
    private static final int BAUD = 115200;

    // Khóa theo cổng để tránh scan & monitor cùng mở một lúc
    private final ConcurrentHashMap<String, ReentrantLock> portLocks = new ConcurrentHashMap<>();

    // ================== SCHEDULES ==================

    /** Full scan 15 phút/lần: inventory + resolve */
    @Scheduled(fixedRate = 900_000)
    public void scheduledFullScan() {
        try {
            syncAndResolve();
        } catch (Exception e) {
            log.error("❌ Lỗi chạy scheduledFullScan: {}", e.getMessage(), e);
        }
    }

    /** Monitor health 1 phút/lần: ping + check CCID */
    @Scheduled(fixedRate = 60_000)
    public void scheduledMonitorActivePorts() {
        try {
            monitorActivePorts();
        } catch (Exception e) {
            log.error("❌ Lỗi chạy scheduledMonitorActivePorts: {}", e.getMessage(), e);
        }
    }

    // ================== PUBLIC MAIN ==================

    /**
     * Luồng chính: scan tất cả COM -> lưu DB -> resolve số cho SIM chưa có số (chỉ khi DB cũng chưa có).
     * Có thể gọi từ API thủ công.
     */
    public void syncAndResolve() throws Exception {
        String deviceName = InetAddress.getLocalHost().getHostName();
        log.info("=== BẮT ĐẦU SCAN cho deviceName={} ===", deviceName);

        // 1) Scan toàn bộ COM
        List<ScannedSim> scanned = scanAllPorts();

        // 2) Log bảng
        logScanResult(deviceName, scanned);

        // 3) Đồng bộ DB
        syncScannedToDb(deviceName, scanned);

        // 4) Chọn receiver & resolve số
        List<ScannedSim> known = scanned.stream()
                .filter(s -> s.phoneNumber != null && !s.phoneNumber.isBlank())
                .toList();

        List<ScannedSim> unknown = scanned.stream()
                .filter(s -> s.phoneNumber == null || s.phoneNumber.isBlank())
                .toList();

        if (known.isEmpty()) {
            log.warn("❌ Không có SIM nào có số điện thoại (CNUM). Bỏ qua bước resolve.");
            return;
        }

        ScannedSim receiver = known.get(0);
        log.info("Receiver chọn: com={} phone={}", receiver.comName, receiver.phoneNumber);

        resolvePhoneNumbers(deviceName, unknown, receiver);
    }

    // ================== SCAN ==================

    /** Scan tất cả cổng COM song song, chỉ nhận SIM nào mở được và có phản hồi AT */
    private List<ScannedSim> scanAllPorts() throws InterruptedException {
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Tìm thấy {} cổng COM", ports.length);

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<Future<ScannedSim>> futures = new ArrayList<>();

        for (SerialPort port : ports) {
            futures.add(pool.submit(() -> scanOnePort(port)));
        }
        pool.shutdown();
        pool.awaitTermination(SCAN_TIMEOUT_MIN, TimeUnit.MINUTES);

        List<ScannedSim> scanned = new ArrayList<>();
        for (Future<ScannedSim> f : futures) {
            try {
                ScannedSim s = f.get();
                if (s != null) scanned.add(s);
            } catch (Exception ignored) {}
        }
        return scanned;
    }

    /** Scan 1 port: mở cổng, gửi AT, lấy CCID/IMSI/CNUM. Dùng khóa theo cổng để không đụng monitor. */
    private ScannedSim scanOnePort(SerialPort port) {
        String com = port.getSystemPortName();
        ReentrantLock lock = portLocks.computeIfAbsent(com, k -> new ReentrantLock());

        if (!tryLock(lock, 2_000)) {
            log.debug("⏭️ Bỏ qua scan {} vì không lấy được lock", com);
            return null;
        }

        try {
            port.setBaudRate(BAUD);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, OPEN_READ_TIMEOUT_MS, OPEN_READ_TIMEOUT_MS);

            if (!port.openPort()) {
                log.debug("❌ Không mở được {}", com);
                return null;
            }

            try (AtCommandHelper helper = new AtCommandHelper(port)) {
                String atResp = helper.sendAndRead("AT", AT_TIMEOUT_MS);
                if (atResp == null || !atResp.contains("OK")) {
                    log.debug("❌ {} không phản hồi AT", com);
                    return null;
                }

                String ccid = helper.getCcid();
                String imsi = helper.getImsi();
                String phone = helper.getCnum(); // nhiều mạng (Rakuten) sẽ null — chấp nhận

                log.info("✅ {} -> ccid={} imsi={} phone={}", com, ccid, imsi, phone);
                return new ScannedSim(com, ccid, imsi, phone, detectProvider(imsi));
            } catch (Exception ex) {
                log.warn("❌ Lỗi khi scan {}: {}", com, ex.getMessage());
                return null;
            } finally {
                if (port.isOpen()) port.closePort();
            }
        } finally {
            lock.unlock();
        }
    }

    // ================== DB SYNC ==================

    /** Lưu kết quả scan & mark replaced an toàn bằng missCount; không overwrite phoneNumber nếu null */
    private void syncScannedToDb(String deviceName, List<ScannedSim> scanned) {
        // 1) Load DB 1 lần
        List<Sim> dbSims = simRepository.findByDeviceName(deviceName);
        Map<String, Sim> dbMap = dbSims.stream()
                .filter(s -> s.getCcid() != null)
                .collect(Collectors.toMap(Sim::getCcid, s -> s, (a, b) -> a));

        Set<String> scannedCcids = new HashSet<>();
        List<Sim> toSave = new ArrayList<>();

        // 2) Upsert những SIM scan được
        for (ScannedSim ss : scanned) {
            if (ss.ccid == null) continue;
            scannedCcids.add(ss.ccid);

            Sim sim = dbMap.getOrDefault(ss.ccid,
                    Sim.builder()
                            .ccid(ss.ccid)
                            .deviceName(deviceName)
                            .comName(ss.comName)
                            .missCount(0)
                            .status("new")
                            .build());

            if ("replaced".equals(sim.getStatus()) || "unhealthy".equals(sim.getStatus())) {
                log.info("♻️ SIM {} (com={}) khôi phục -> active", ss.ccid, ss.comName);
            }

            sim.setMissCount(0);
            sim.setStatus("active");
            sim.setImsi(ss.imsi);
            sim.setComName(ss.comName);
            if (ss.phoneNumber != null && !ss.phoneNumber.isBlank()) {
                // ❗ Không overwrite null lên DB
                sim.setPhoneNumber(ss.phoneNumber);
            }
            sim.setSimProvider(ss.simProvider);
            sim.setLastUpdated(Instant.now());

            toSave.add(sim);
        }

        // 3) SIM trong DB nhưng không thấy ở scan -> tăng miss, đủ ngưỡng mới replaced
        for (Sim db : dbSims) {
            if (db.getCcid() != null && !scannedCcids.contains(db.getCcid())) {
                db.setMissCount(db.getMissCount() + 1);

                if (db.getMissCount() >= MISS_THRESHOLD && "active".equals(db.getStatus())) {
                    db.setStatus("replaced");
                    log.info("⚠️ SIM {} (com={}) đánh replaced sau {} lần không thấy",
                            db.getCcid(), db.getComName(), db.getMissCount());
                } else {
                    log.debug("⏳ SIM {} (com={}) chưa thấy lần {}", db.getCcid(), db.getComName(), db.getMissCount());
                }
                db.setLastUpdated(Instant.now());
                toSave.add(db);
            }
        }

        // 4) SaveAll 1 lần
        if (!toSave.isEmpty()) simRepository.saveAll(toSave);
    }

    // ================== RESOLVE PHONE VIA SMS ==================

    /** Chỉ gửi SMS test khi DB KHÔNG có số. Gom saveAll để giảm query. */
    private void resolvePhoneNumbers(String deviceName, List<ScannedSim> unknown, ScannedSim receiver) {
        Map<String, ScannedSim> tokenMap = new HashMap<>();

        // 1. Gửi SMS test
        for (ScannedSim sim : unknown) {
            if (sim.ccid == null) continue;

            String token = "CHECK-" + UUID.randomUUID().toString().substring(0, 6);
            boolean sent = sendSmsFromPort(sim.comName, receiver.phoneNumber, token);
            if (sent) {
                tokenMap.put(token, sim);
                log.info("👉 Gửi token={} từ {} -> {}", token, sim.comName, receiver.phoneNumber);
            }
        }

        if (tokenMap.isEmpty()) return;

        // 2. Đọc inbox từ receiver duy nhất
        readAllTokensFromReceiver(deviceName, receiver.comName, tokenMap, 20_000);
    }

    private void readAllTokensFromReceiver(String deviceName, String receiverCom,
                                           Map<String, ScannedSim> tokenMap, long timeoutMs) {
        long start = System.currentTimeMillis();
        Map<String, String> resolved = new HashMap<>();

        while (System.currentTimeMillis() - start < timeoutMs && resolved.size() < tokenMap.size()) {
            Boolean ok = portManager.withPort(receiverCom, helper -> {
                try {
                    var unread = helper.listUnreadSmsText(3000);
                    for (var sms : unread) {
                        for (var entry : tokenMap.entrySet()) {
                            if (sms.body != null && sms.body.contains(entry.getKey())) {
                                ScannedSim sim = entry.getValue();
                                if (!resolved.containsKey(sim.ccid)) {
                                    resolved.put(sim.ccid, sms.sender);
                                    updateDb(deviceName, sim, sms.sender);
                                    log.info("✅ Resolve SIM ccid={} com={} phone={}",
                                            sim.ccid, sim.comName, sms.sender);
                                }
                            }
                        }
                    }
                    return true;
                } catch (Exception e) {
                    log.error("❌ Lỗi đọc inbox {}: {}", receiverCom, e.getMessage());
                    return false;
                }
            }, 3000);

            if (ok == null || !ok) {
                log.warn("⚠️ Receiver {} không đọc được trong tick này", receiverCom);
            }

            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }
    }


    private void updateDb(String deviceName, ScannedSim sim, String phoneNumber) {
        Sim dbSim = simRepository.findByDeviceNameAndCcid(deviceName, sim.ccid)
                .orElse(Sim.builder()
                        .ccid(sim.ccid)
                        .deviceName(deviceName)
                        .comName(sim.comName)
                        .build());

        dbSim.setPhoneNumber(phoneNumber);
        dbSim.setStatus("active");
        dbSim.setLastUpdated(Instant.now());

        simRepository.save(dbSim);
    }

    private boolean sendSmsFromPort(String fromCom, String toNumber, String token) {
        Boolean result = portManager.withPort(fromCom, helper -> {
            try {
                return helper.sendTextSms(toNumber, token, Duration.ofSeconds(15));
            } catch (Exception e) {
                log.error("Gửi SMS lỗi từ {}: {}", fromCom, e.getMessage());
                return false;
            }
        }, 2500);
        return result != null && result;
    }

    private String pollReceiverForToken(String receiverCom, String token, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            String sender = withPort(receiverCom, helper -> {
                try {
                    var unread = helper.listUnreadSmsText(3_000);
                    for (var sms : unread) {
                        if (sms.body != null && sms.body.contains(token)) {
                            return sms.sender;
                        }
                    }
                    return null;
                } catch (Exception e) {
                    log.error("Đọc inbox receiver {} lỗi: {}", receiverCom, e.getMessage());
                    return null;
                }
            }, 3_000);

            if (sender != null) return sender;
            try { Thread.sleep(2_000); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    // ================== MONITOR HEALTH (1 phút) ==================

    private void monitorActivePorts() throws Exception {
        String deviceName = InetAddress.getLocalHost().getHostName();

        // Chỉ theo dõi SIM của máy hiện tại để tránh đụng chéo
        List<Sim> sims = simRepository.findByDeviceName(deviceName);
        List<Sim> activeSims = sims.stream()
                .filter(s -> "active".equalsIgnoreCase(s.getStatus()))
                .toList();

        List<Sim> toSave = new ArrayList<>();

        for (Sim sim : activeSims) {
            String com = sim.getComName();
            // Ping an toàn: nếu không lấy được lock → skip, KHÔNG tăng missCount
            Boolean alive = withPort(com, helper -> {
                try {
                    String resp = helper.sendAndRead("AT", AT_TIMEOUT_MS);
                    return resp != null && resp.contains("OK");
                } catch (Exception e) {
                    return false;
                }
            }, MONITOR_LOCK_TIMEOUT_MS);

            if (Boolean.FALSE.equals(alive)) {
                sim.setMissCount(sim.getMissCount() + 1);
                if (sim.getMissCount() >= MISS_THRESHOLD) {
                    sim.setStatus("unhealthy");
                    log.warn("⚠️ SIM {} (com={}) mark unhealthy (missCount={})",
                            sim.getCcid(), sim.getComName(), sim.getMissCount());
                }
            } else if (Boolean.TRUE.equals(alive)) {
                // Đang alive → kiểm tra CCID thay đổi (phát hiện thay SIM nhanh)
                String newCcid = withPort(com, helper -> {
                    try {
                        return helper.getCcid();
                    } catch (Exception e) {
                        return null;
                    }
                }, MONITOR_LOCK_TIMEOUT_MS);

                if (newCcid != null && sim.getCcid() != null && !newCcid.equals(sim.getCcid())) {
                    log.info("🔄 SIM changed on {} oldCCID={} newCCID={}", com, sim.getCcid(), newCcid);
                    sim.setCcid(newCcid);
                    // có thể đặt 'new' hoặc giữ 'active' tùy policy
                    sim.setStatus("active");
                }

                if ("unhealthy".equalsIgnoreCase(sim.getStatus())) {
                    log.info("♻️ SIM {} (com={}) khôi phục unhealthy -> active", sim.getCcid(), com);
                }
                sim.setMissCount(0);
                sim.setStatus("active");
            } else {
                // alive == null => không acquire được lock, bỏ qua tick này, không tăng miss
                log.debug("⏭️ Bỏ qua monitor {} vì không lấy được lock", com);
            }

            sim.setLastUpdated(Instant.now());
            toSave.add(sim);
        }

        if (!toSave.isEmpty()) simRepository.saveAll(toSave);
    }

    // ================== HELPERS ==================

    private void logScanResult(String deviceName, List<ScannedSim> scanned) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Scan Result for ").append(deviceName).append(" ===\n");
        sb.append(String.format("%-8s %-8s %-15s %-15s %-20s %-10s\n",
                "COM", "Status", "SIM Provider", "Phone Number", "CCID", "Content"));

        for (ScannedSim s : scanned) {
            sb.append(String.format("%-8s %-8s %-15s %-15s %-20s %-10s\n",
                    s.comName,
                    (s.ccid == null ? "ERROR" : "SUCCESS"),
                    (s.simProvider != null ? s.simProvider : ""),
                    (s.phoneNumber != null ? s.phoneNumber : ""),
                    (s.ccid != null ? s.ccid : ""),
                    (s.ccid == null ? "TIMEOUT" : "Registered")));
        }
        log.info(sb.toString());
    }

    private String detectProvider(String imsi) {
        if (imsi == null) return "Unknown";
        if (imsi.startsWith("44010")) return "NTT Docomo (JP)";
        if (imsi.startsWith("44011")) return "Rakuten Mobile (JP)"; // Rakuten thường không có CNUM
        if (imsi.startsWith("45204") || imsi.startsWith("45205")) return "Viettel (VN)";
        return "Unknown";
    }

    /** Generic: mở port theo com với lock an toàn, chạy hàm và đóng port */
    private <T> T withPort(String com, Function<AtCommandHelper, T> fn, long lockTimeoutMs) {
        ReentrantLock lock = portLocks.computeIfAbsent(com, k -> new ReentrantLock());

        if (!tryLock(lock, lockTimeoutMs)) {
            return null; // không lấy được khóa => bỏ qua
        }

        SerialPort port = SerialPort.getCommPort(com);
        try {
            port.setBaudRate(BAUD);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, OPEN_READ_TIMEOUT_MS, OPEN_READ_TIMEOUT_MS);

            if (!port.openPort()) return null;

            try (AtCommandHelper helper = new AtCommandHelper(port)) {
                return fn.apply(helper);
            } finally {
                if (port.isOpen()) port.closePort();
            }
        } catch (Exception e) {
            return null;
        } finally {
            lock.unlock();
        }
    }

    private boolean tryLock(ReentrantLock lock, long timeoutMs) {
        try {
            return lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // DTO scan tạm
    private record ScannedSim(String comName, String ccid, String imsi, String phoneNumber, String simProvider) {}
}
