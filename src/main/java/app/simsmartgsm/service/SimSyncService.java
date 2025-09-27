package app.simsmartgsm.service;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.SimRepository;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimSyncService {

    private final SimRepository simRepository;
    private final PortManager portManager;

    // ==== CONFIG ====
    private static final int THREAD_POOL_SIZE = 8;
    private static final long SCAN_TIMEOUT_MIN = 1;
    private static final int MISS_THRESHOLD = 3;

    // chạy mỗi 100 giây
    @Scheduled(fixedRate = 500_000)
    public void scheduledFullScan() {
        try {
            syncAndResolve();
        } catch (Exception e) {
            log.error("❌ Lỗi chạy scheduledFullScan: {}", e.getMessage(), e);
        }
    }

    // ================== PUBLIC MAIN ==================

    public void syncAndResolve() throws Exception {
        String deviceName = InetAddress.getLocalHost().getHostName();
        log.info("=== BẮT ĐẦU SCAN cho deviceName={} ===", deviceName);

        // 1) Scan toàn bộ COM
        List<ScannedSim> scanned = scanAllPorts();

        // 2) Log kết quả
        logScanResult(deviceName, scanned);

        // 3) Đồng bộ DB
        syncScannedToDb(deviceName, scanned);
    }

    // ================== SCAN ==================

    /** Scan tất cả cổng COM song song */
    private List<ScannedSim> scanAllPorts() throws InterruptedException {
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Tìm thấy {} cổng COM", ports.length);

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<Future<ScannedSim>> futures = new ArrayList<>();

        for (SerialPort port : ports) {
            futures.add(pool.submit(() -> scanOnePort(port.getSystemPortName())));
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

    /** Scan 1 port: chỉ trả về nếu có số điện thoại */
    private ScannedSim scanOnePort(String com) {
        return portManager.withPort(com, helper -> {
            try {
                String ccid = helper.getCcid();
                String imsi = helper.getImsi();
                String phone = helper.getCnum();

                if (ccid == null || phone == null || phone.isBlank()) {
                    log.debug("❌ {} bỏ qua vì không lấy được CCID hoặc số", com);
                    return null;
                }

                log.info("✅ {} -> ccid={} imsi={} phone={}", com, ccid, imsi, phone);
                return new ScannedSim(com, ccid, imsi, phone, detectProvider(imsi));
            } catch (Exception ex) {
                log.warn("❌ Lỗi khi scan {}: {}", com, ex.getMessage());
                return null;
            }
        }, 3000L);
    }

    // ================== DB SYNC ==================

    private void syncScannedToDb(String deviceName, List<ScannedSim> scanned) {
        List<Sim> dbSims = simRepository.findByDeviceName(deviceName);
        Map<String, Sim> dbMap = dbSims.stream()
                .filter(s -> s.getCcid() != null)
                .collect(Collectors.toMap(Sim::getCcid, s -> s, (a, b) -> a));

        Set<String> scannedCcids = new HashSet<>();
        List<Sim> toSave = new ArrayList<>();

        // ✅ 1. Các SIM đang thấy trong scan => ACTIVE
        for (ScannedSim ss : scanned) {
            if (ss.ccid == null) continue;
            scannedCcids.add(ss.ccid);

            Sim sim = dbMap.getOrDefault(ss.ccid,
                    Sim.builder()
                            .ccid(ss.ccid)
                            .deviceName(deviceName)
                            .comName(ss.comName)
                            .missCount(0)
                            .status("active")
                            .build()
            );

            sim.setMissCount(0);
            sim.setStatus("active");
            sim.setImsi(ss.imsi);
            sim.setComName(ss.comName);
            sim.setPhoneNumber(ss.phoneNumber);
            sim.setSimProvider(ss.simProvider);
            sim.setLastUpdated(Instant.now());

            toSave.add(sim);
        }

        // ❌ 2. Các SIM trong DB nhưng lần này không thấy
        for (Sim db : dbSims) {
            if (db.getCcid() != null && !scannedCcids.contains(db.getCcid())) {
                db.setMissCount(db.getMissCount() + 1);

                if (db.getMissCount() >= MISS_THRESHOLD) {
                    db.setStatus("replaced");
                    log.info("⚠️ SIM {} (com={}) chuyển sang REPLACED sau {} lần không thấy",
                            db.getCcid(), db.getComName(), db.getMissCount());
                }
                db.setLastUpdated(Instant.now());
                toSave.add(db);
            }
        }

        if (!toSave.isEmpty()) {
            simRepository.saveAll(toSave);
        }
    }

    // ================== HELPERS ==================

    private void logScanResult(String deviceName, List<ScannedSim> scanned) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Scan Result for ").append(deviceName).append(" ===\n");
        sb.append(String.format("%-8s %-8s %-15s %-15s %-20s\n",
                "COM", "Status", "Provider", "Phone Number", "CCID"));

        for (ScannedSim s : scanned) {
            sb.append(String.format("%-8s %-8s %-15s %-15s %-20s\n",
                    s.comName,
                    "ACTIVE",
                    (s.simProvider != null ? s.simProvider : ""),
                    (s.phoneNumber != null ? s.phoneNumber : ""),
                    (s.ccid != null ? s.ccid : "")));
        }
        log.info(sb.toString());
    }

    private String detectProvider(String imsi) {
        if (imsi == null) return "Unknown";
        if (imsi.startsWith("44010")) return "NTT Docomo (JP)";
        if (imsi.startsWith("44011")) return "Rakuten Mobile (JP)";
        if (imsi.startsWith("45204") || imsi.startsWith("45205")) return "Viettel (VN)";
        return "Unknown";
    }

    // DTO tạm cho scan
    private record ScannedSim(String comName, String ccid, String imsi, String phoneNumber, String simProvider) {}
}
