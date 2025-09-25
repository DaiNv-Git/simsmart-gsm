package app.simsmartgsm.service;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        // 1. Lấy toàn bộ SIM của deviceName (1 query duy nhất)
        List<Sim> dbSims = simRepository.findByDeviceName(deviceName);
        Map<String, Sim> dbMap = dbSims.stream()
                .filter(s -> s.getCcid() != null)
                .collect(Collectors.toMap(Sim::getCcid, s -> s, (a, b) -> a));

        Set<String> scannedCcids = new HashSet<>();
        List<Sim> toSave = new ArrayList<>();

        // 2. Xử lý SIM quét được
        for (ScannedSim ss : scanned) {
            if (ss.ccid == null) continue;
            scannedCcids.add(ss.ccid);

            Sim sim = dbMap.getOrDefault(ss.ccid,
                    Sim.builder()
                            .ccid(ss.ccid)
                            .deviceName(deviceName)
                            .comName(ss.comName)
                            .status("new") // nếu chưa có thì đánh là new
                            .build());

            // Nếu SIM từng bị replaced → khôi phục lại thành active
            if ("replaced".equals(sim.getStatus())) {
                log.info("♻️ SIM ccid={} com={} được khôi phục từ replaced -> active", ss.ccid, ss.comName);
                sim.setStatus("active");
            } else {
                sim.setStatus("active");
            }

            sim.setImsi(ss.imsi);
            sim.setComName(ss.comName);
            if (ss.phoneNumber != null) sim.setPhoneNumber(ss.phoneNumber);
            sim.setSimProvider(ss.simProvider);
            sim.setLastUpdated(Instant.now());

            toSave.add(sim);
        }

        // 3. Xử lý SIM trong DB mà không còn thấy trong scan
        for (Sim db : dbSims) {
            if (db.getCcid() != null && !scannedCcids.contains(db.getCcid())) {
                if ("active".equals(db.getStatus())) {
                    db.setStatus("replaced");
                    db.setLastUpdated(Instant.now());
                    toSave.add(db);
                    log.info("⚠️ SIM {} (com={}) đánh dấu replaced", db.getCcid(), db.getComName());
                }
                // nếu đã replaced rồi thì giữ nguyên, không update thêm
            }
        }

        // 4. Save tất cả 1 lần
        if (!toSave.isEmpty()) {
            simRepository.saveAll(toSave);
        }
    }


    private void resolvePhoneNumbers(String deviceName, List<ScannedSim> unknown, ScannedSim receiver) {
        for (ScannedSim sim : unknown) {
            if (sim.ccid == null) continue;

            // 👉 check DB trước
            Optional<Sim> dbSimOpt = simRepository.findByDeviceNameAndCcid(deviceName, sim.ccid);
            if (dbSimOpt.isPresent() && dbSimOpt.get().getPhoneNumber() != null) {
                log.info("⏩ Bỏ qua SIM com={} ccid={} vì DB đã có số {}",
                        sim.comName, sim.ccid, dbSimOpt.get().getPhoneNumber());
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

                Sim dbSim = dbSimOpt.orElse(Sim.builder()
                        .ccid(sim.ccid)
                        .deviceName(deviceName)
                        .comName(sim.comName)
                        .build());
                dbSim.setPhoneNumber(found);
                dbSim.setStatus("active");
                dbSim.setLastUpdated(Instant.now());
                simRepository.save(dbSim);
            }
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

    private record ScannedSim(String comName, String ccid, String imsi, String phoneNumber, String simProvider) {}
}
