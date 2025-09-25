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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimSyncService {

    private final SimRepository simRepository;

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

    private List<ScannedSim> scanAllPorts() {
        List<ScannedSim> scanned = new ArrayList<>();
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Tìm thấy {} cổng COM", ports.length);

        for (SerialPort port : ports) {
            String com = port.getSystemPortName();
            try (AtCommandHelper helper = new AtCommandHelper(port)) {
                helper.ping();
                String ccid = helper.getCcid();
                String imsi = helper.getImsi();
                String phone = helper.getCnum();

                ScannedSim s = new ScannedSim(com, ccid, imsi, phone, detectProvider(imsi));
                scanned.add(s);

                log.info("✅ Scan {} -> ccid={} imsi={} phone={}", com, ccid, imsi, phone);
            } catch (Exception ex) {
                log.warn("❌ Lỗi scan port {}: {}", com, ex.getMessage());
            }
        }
        return scanned;
    }

    private void syncScannedToDb(String deviceName, List<ScannedSim> scanned) {
        for (ScannedSim ss : scanned) {
            if (ss.ccid == null) continue;

            Sim sim = simRepository.findByDeviceNameAndCcid(deviceName, ss.ccid)
                    .orElse(Sim.builder().ccid(ss.ccid).deviceName(deviceName).comName(ss.comName).build());

            sim.setImsi(ss.imsi);
            sim.setComName(ss.comName);
            sim.setPhoneNumber(ss.phoneNumber);
            sim.setSimProvider(ss.simProvider);
            sim.setStatus("active");
            sim.setLastUpdated(Instant.now());

            simRepository.save(sim);
        }

        // đánh dấu replaced cho sim mất
        Set<String> scannedCcids = scanned.stream().map(s -> s.ccid).filter(Objects::nonNull).collect(Collectors.toSet());
        simRepository.findByDeviceName(deviceName).forEach(db -> {
            if (db.getCcid() != null && !scannedCcids.contains(db.getCcid())) {
                db.setStatus("replaced");
                db.setLastUpdated(Instant.now());
                simRepository.save(db);
                log.info("⚠️ SIM {} (com={}) đánh dấu replaced", db.getCcid(), db.getComName());
            }
        });
    }

    private void resolvePhoneNumbers(String deviceName, List<ScannedSim> unknown, ScannedSim receiver) {
        for (ScannedSim sim : unknown) {
            String token = "CHECK-" + UUID.randomUUID().toString().substring(0, 6);
            log.info("👉 Gửi token={} từ {} -> {}", token, sim.comName, receiver.phoneNumber);

            boolean sent = sendSmsFromPort(sim.comName, receiver.phoneNumber, token);
            if (!sent) continue;

            String found = pollReceiverForToken(receiver.comName, token, 20_000);
            if (found != null) {
                log.info("✅ Resolve thành công: com={} ccid={} phone={}", sim.comName, sim.ccid, found);

                Sim dbSim = simRepository.findByDeviceNameAndCcid(deviceName, sim.ccid)
                        .orElse(Sim.builder().ccid(sim.ccid).deviceName(deviceName).comName(sim.comName).build());
                dbSim.setPhoneNumber(found);
                dbSim.setStatus("active");
                dbSim.setLastUpdated(Instant.now());
                simRepository.save(dbSim);
            }
        }
    }

    private boolean sendSmsFromPort(String fromCom, String toNumber, String token) {
        try (AtCommandHelper helper = new AtCommandHelper(SerialPort.getCommPort(fromCom))) {
            return helper.sendTextSms(toNumber, token, java.time.Duration.ofSeconds(15));
        } catch (Exception e) {
            log.error("Gửi SMS lỗi từ {}: {}", fromCom, e.getMessage());
            return false;
        }
    }

    private String pollReceiverForToken(String receiverCom, String token, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try (AtCommandHelper helper = new AtCommandHelper(SerialPort.getCommPort(receiverCom))) {
                var unread = helper.listUnreadSmsText(3000);
                for (var sms : unread) {
                    if (sms.body != null && sms.body.contains(token)) {
                        return sms.sender;
                    }
                }
            } catch (Exception e) {
                log.error("Đọc inbox receiver {} lỗi: {}", receiverCom, e.getMessage());
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
