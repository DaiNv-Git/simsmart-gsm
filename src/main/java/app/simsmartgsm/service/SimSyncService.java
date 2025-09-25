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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimSyncService {

    private final SimRepository simRepository;

    private static final int THREAD_POOL_SIZE = 8; // scan song song
    private static final int AT_TIMEOUT_MS = 1000;

    /**
     * Luồng chính: scan tất cả COM -> lưu DB -> resolve số điện thoại cho SIM chưa có số.
     */
    @Scheduled(fixedRate = 900_000)
    public void syncAndResolve() throws Exception {
        String deviceName = InetAddress.getLocalHost().getHostName();
        log.info("=== BẮT ĐẦU SCAN cho deviceName={} ===", deviceName);

        // 1. Scan tất cả COM
        List<ScannedSim> scanned = scanAllPorts();

        // 2. Lưu DB
        syncScannedToDb(deviceName, scanned);

        // 3. Chọn receiver
        List<ScannedSim> known = scanned.stream()
                .filter(s -> s.phoneNumber != null && !s.phoneNumber.isBlank())
                .toList();

        List<ScannedSim> unknown = scanned.stream()
                .filter(s -> s.phoneNumber == null || s.phoneNumber.isBlank())
                .toList();

        if (known.isEmpty()) {
            log.warn("❌ Không có SIM nào có số điện thoại. Bỏ qua bước resolve.");
            return;
        }

        ScannedSim receiver = known.get(0);
        log.info("Receiver chọn: com={} phone={}", receiver.comName, receiver.phoneNumber);

        // 4. Resolve cho các SIM chưa biết số
        resolvePhoneNumbers(deviceName, unknown, receiver);
    }

    /**
     * Scan tất cả cổng COM song song, chỉ nhận SIM nào mở port được và có phản hồi AT.
     */
    private List<ScannedSim> scanAllPorts() throws InterruptedException {
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Tìm thấy {} cổng COM", ports.length);

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<Future<ScannedSim>> futures = new ArrayList<>();

        for (SerialPort port : ports) {
            futures.add(pool.submit(() -> scanOnePort(port)));
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);

        List<ScannedSim> scanned = new ArrayList<>();
        for (Future<ScannedSim> f : futures) {
            try {
                ScannedSim s = f.get();
                if (s != null) scanned.add(s);
            } catch (Exception ignored) {}
        }
        return scanned;
    }

    /**
     * Scan 1 port: mở cổng, gửi AT, lấy CCID/IMSI/CNUM.
     */
    private ScannedSim scanOnePort(SerialPort port) {
        String com = port.getSystemPortName();
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);

        if (!port.openPort()) {
            log.debug("❌ Không mở được {}", com);
            return null;
        }

        try (AtCommandHelper helper = new AtCommandHelper(port)) {
            // test AT
            String atResp = helper.sendAndRead("AT", AT_TIMEOUT_MS);
            if (atResp == null || !atResp.contains("OK")) {
                log.debug("❌ {} không phản hồi AT", com);
                return null;
            }

            String ccid = helper.getCcid();
            String imsi = helper.getImsi();
            String phone = helper.getCnum();

            log.info("✅ {} -> ccid={} imsi={} phone={}", com, ccid, imsi, phone);
            return new ScannedSim(com, ccid, imsi, phone, detectProvider(imsi));
        } catch (Exception ex) {
            log.warn("❌ Lỗi khi scan {}: {}", com, ex.getMessage());
            return null;
        } finally {
            port.closePort();
        }
    }

    /**
     * Lưu kết quả scan vào DB và mark replaced cho SIM active không còn xuất hiện.
     */
    private void syncScannedToDb(String deviceName, List<ScannedSim> scanned) {
        List<Sim> dbSims = simRepository.findByDeviceName(deviceName);
        Map<String, Sim> dbMap = dbSims.stream()
                .filter(s -> s.getCcid() != null)
                .collect(Collectors.toMap(Sim::getCcid, s -> s, (a, b) -> a));

        Set<String> scannedCcids = new HashSet<>();
        List<Sim> toSave = new ArrayList<>();

        // SIM scan được
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

            // ♻️ Nếu trước đó bị replaced → khôi phục lại active
            if ("replaced".equals(sim.getStatus())) {
                log.info("♻️ SIM {} (com={}) được khôi phục từ replaced -> active",
                        ss.ccid, ss.comName);
            }

            // reset missCount
            sim.setMissCount(0);
            sim.setStatus("active");
            sim.setImsi(ss.imsi);
            sim.setComName(ss.comName);
            if (ss.phoneNumber != null) sim.setPhoneNumber(ss.phoneNumber);
            sim.setSimProvider(ss.simProvider);
            sim.setLastUpdated(Instant.now());

            toSave.add(sim);
        }


        // SIM không thấy trong scan
        for (Sim db : dbSims) {
            if (db.getCcid() != null && !scannedCcids.contains(db.getCcid())) {
                db.setMissCount(db.getMissCount() + 1);

                if (db.getMissCount() >= 3 && "active".equals(db.getStatus())) {
                    db.setStatus("replaced");
                    log.info("⚠️ SIM {} (com={}) bị đánh replaced sau {} lần không thấy",
                            db.getCcid(), db.getComName(), db.getMissCount());
                } else {
                    log.debug("⏳ SIM {} (com={}) chưa thấy lần {}",
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


    private void resolvePhoneNumbers(String deviceName, List<ScannedSim> unknown, ScannedSim receiver) {
        // Load toàn bộ SIM của deviceName một lần
        List<Sim> dbSims = simRepository.findByDeviceName(deviceName);
        Map<String, Sim> dbMap = dbSims.stream()
                .filter(s -> s.getCcid() != null)
                .collect(Collectors.toMap(Sim::getCcid, s -> s, (a, b) -> a));

        List<Sim> toSave = new ArrayList<>();

        for (ScannedSim sim : unknown) {
            if (sim.ccid == null) continue;

            Sim dbSim = dbMap.get(sim.ccid);

            // 👉 Nếu DB đã có số thì gán lại cho sim, không cần gửi SMS nữa
            if (dbSim != null && dbSim.getPhoneNumber() != null) {
                log.info("⏩ SIM com={} ccid={} đã có số {} trong DB, dùng luôn",
                        sim.comName, sim.ccid, dbSim.getPhoneNumber());
                // cập nhật lại ScannedSim để lần sau không coi là unknown nữa
                sim = new ScannedSim(sim.comName, sim.ccid, sim.imsi,
                        dbSim.getPhoneNumber(), sim.simProvider);
                continue;
            }

            // chưa có số trong DB => mới gửi SMS test
            String token = "CHECK-" + UUID.randomUUID().toString().substring(0, 6);
            log.info("👉 Gửi token={} từ {} -> {}", token, sim.comName, receiver.phoneNumber);

            boolean sent = sendSmsFromPort(sim.comName, receiver.phoneNumber, token);
            if (!sent) continue;

            String found = pollReceiverForToken(receiver.comName, token, 20_000);
            if (found != null) {
                log.info("✅ Resolve thành công: com={} ccid={} phone={}", sim.comName, sim.ccid, found);

                if (dbSim == null) {
                    dbSim = Sim.builder()
                            .ccid(sim.ccid)
                            .deviceName(deviceName)
                            .comName(sim.comName)
                            .build();
                }
                dbSim.setPhoneNumber(found);
                dbSim.setStatus("active");
                dbSim.setLastUpdated(Instant.now());
                toSave.add(dbSim);
            }
        }

        // saveAll một lần
        if (!toSave.isEmpty()) {
            simRepository.saveAll(toSave);
        }
    }

    private boolean sendSmsFromPort(String fromCom, String toNumber, String token) {
        SerialPort port = SerialPort.getCommPort(fromCom);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!port.openPort()) return false;

        try (AtCommandHelper helper = new AtCommandHelper(port)) {
            return helper.sendTextSms(toNumber, token, java.time.Duration.ofSeconds(15));
        } catch (Exception e) {
            log.error("Gửi SMS lỗi từ {}: {}", fromCom, e.getMessage());
            return false;
        } finally {
            port.closePort();
        }
    }

    private String pollReceiverForToken(String receiverCom, String token, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            SerialPort port = SerialPort.getCommPort(receiverCom);
            port.setBaudRate(115200);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);

            if (!port.openPort()) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                continue;
            }

            try (AtCommandHelper helper = new AtCommandHelper(port)) {
                var unread = helper.listUnreadSmsText(3000);
                for (var sms : unread) {
                    if (sms.body != null && sms.body.contains(token)) {
                        return sms.sender;
                    }
                }
            } catch (Exception e) {
                log.error("Đọc inbox receiver {} lỗi: {}", receiverCom, e.getMessage());
            } finally {
                port.closePort();
            }

            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private String detectProvider(String imsi) {
        if (imsi == null) return null;
        if (imsi.startsWith("44010")) return "NTT Docomo (JP)";
        if (imsi.startsWith("44011")) return "Rakuten Mobile (JP)";
        if (imsi.startsWith("45204") || imsi.startsWith("45205")) return "Viettel (VN)";
        return "Unknown";
    }

    @Scheduled(fixedRate = 60_000) // 1 phút
    public void scheduledMonitorActivePorts() {
        try {
            log.info("🕒 Scheduled HEALTH CHECK running...");
            monitorActivePorts();
        } catch (Exception e) {
            log.error("❌ Lỗi khi chạy health check: {}", e.getMessage(), e);
        }
    }

    private void monitorActivePorts() {
        List<Sim> activeSims = simRepository.findByStatus("active");
        List<Sim> toSave = new ArrayList<>();

        for (Sim sim : activeSims) {
            boolean alive = pingSimPort(sim.getComName());

            if (!alive) {
                sim.setMissCount(sim.getMissCount() + 1);
                if (sim.getMissCount() >= 3) {
                    sim.setStatus("unhealthy");
                    log.warn("⚠️ SIM {} (com={}) mark unhealthy sau {} lần fail",
                            sim.getCcid(), sim.getComName(), sim.getMissCount());
                }
            } else {
                // ✅ Khi còn alive, kiểm tra CCID có thay đổi không
                checkSimIdentity(sim);

                if ("unhealthy".equals(sim.getStatus())) {
                    log.info("♻️ SIM {} (com={}) khôi phục từ unhealthy -> active",
                            sim.getCcid(), sim.getComName());
                }
                sim.setMissCount(0);
                sim.setStatus("active");
            }
            sim.setLastUpdated(Instant.now());
            toSave.add(sim);
        }

        if (!toSave.isEmpty()) {
            simRepository.saveAll(toSave);
        }
    }

    private boolean pingSimPort(String com) {
        SerialPort port = SerialPort.getCommPort(com);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

        if (!port.openPort()) return false;

        try (AtCommandHelper helper = new AtCommandHelper(port)) {
            String resp = helper.sendAndRead("AT", AT_TIMEOUT_MS);
            return resp != null && resp.contains("OK");
        } catch (Exception e) {
            return false;
        } finally {
            port.closePort();
        }
    }


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

    private boolean checkSimIdentity(Sim sim) {
        SerialPort port = SerialPort.getCommPort(sim.getComName());
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1500, 1500);

        if (!port.openPort()) return false;

        try (AtCommandHelper helper = new AtCommandHelper(port)) {
            String ccid = helper.getCcid();
            if (ccid != null && !ccid.equals(sim.getCcid())) {
                log.info("🔄 SIM changed on com={} oldCCID={} newCCID={}",
                        sim.getComName(), sim.getCcid(), ccid);
                sim.setCcid(ccid);
                sim.setStatus("new"); // hoặc "active" tuỳ policy
                sim.setLastUpdated(Instant.now());
                simRepository.save(sim);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            port.closePort();
        }
    }


    private record ScannedSim(String comName, String ccid, String imsi, String phoneNumber, String simProvider) {}
}
