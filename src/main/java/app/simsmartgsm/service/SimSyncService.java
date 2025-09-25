package app.simsmartgsm.service;


import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service đồng bộ SIM + lấy phoneNumber bằng cách bắt các SIM chưa có số "gửi" 1 SMS tới SIM đã biết số (receiver).
 */
@Service
@RequiredArgsConstructor
public class SimSyncService {
    private static final Logger log = LoggerFactory.getLogger(SimSyncService.class);

    private final SimRepository simRepository;

    // cấu hình
    private static final long RECEIVE_POLL_INTERVAL_MS = 1500;
    private static final long RECEIVE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20); // time chờ tin nhắn đến trên receiver
    private static final int MAX_RECEIVER_TO_TRY = 3; // thử tối đa 3 receiver (COM đầu tiên, 2,3)
    private static final int SMS_SEND_RETRY = 2;

    /**
     * Entry point: quét tất cả port, cập nhật DB, rồi lấy số cho các SIM chưa có phoneNumber
     */
    public static String getDeviceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getenv().getOrDefault("COMPUTERNAME", "UNKNOWN");
        }
    }
    public void syncAndResolvePhoneNumbers() {
        String deviceName= getDeviceName();
        log.info("Start syncAndResolvePhoneNumbers for deviceName={}", deviceName);

        List<ScannedSim> scanned = scanAllPorts(deviceName);

        // 2. Sync: insert new active, update lastUpdated
        syncScannedToDb(deviceName, scanned);

        // 3. Chọn receiver candidates (ports có phoneNumber)
        List<ScannedSim> receivers = selectReceivers(scanned);
        if (receivers.isEmpty()) {
            log.warn("Không tìm được receiver (port có phoneNumber). Không thể tiếp tục bước lấy số.");
            return;
        }

        // Trim to first N receivers
        receivers = receivers.stream().limit(MAX_RECEIVER_TO_TRY).collect(Collectors.toList());
        log.info("Receiver candidates: {}", receivers.stream().map(s -> s.comName + "/" + s.phoneNumber).collect(Collectors.joining(",")));

        // 4. Tạo map ccid->Sim entity để dễ update
        Map<String, Sim> dbMap = simRepository.findByDeviceName(deviceName).stream()
                .collect(Collectors.toMap(Sim::getCcid, s -> s));

        // 5. For each scanned sim missing phoneNumber -> request it send to receivers
        for (ScannedSim s : scanned) {
            if (s.phoneNumber != null && !s.phoneNumber.isBlank()) continue; // đã có số

            if (s.ccid == null || s.ccid.isBlank()) {
                log.warn("Port {} không đọc được CCID, bỏ qua", s.comName);
                continue;
            }

            log.info("Thử resolve phoneNumber cho CCID={} (com={})", s.ccid, s.comName);

            boolean resolved = false;
            for (ScannedSim receiver : receivers) {
                if (receiver.comName.equalsIgnoreCase(s.comName)) {
                    // không cho port tự gửi tới chính nó
                    continue;
                }

                for (int attempt = 1; attempt <= SMS_SEND_RETRY && !resolved; attempt++) {
                    String token = "CHECK-" + UUID.randomUUID().toString().substring(0, 6);
                    log.info("Gửi token={} từ {} -> {} (attempt {}/{})", token, s.comName, receiver.phoneNumber, attempt, SMS_SEND_RETRY);

                    boolean sendOk = sendSmsFromPort(s.comName, receiver.phoneNumber, token);
                    if (!sendOk) {
                        log.warn("Gửi SMS từ {} tới {} thất bại (attempt {}).", s.comName, receiver.phoneNumber, attempt);
                        continue; // thử lại
                    }

                    // Poll receiver inbox để tìm tin nhắn chứa token (vì sender sẽ là số cần lấy)
                    String foundSenderNumber = pollReceiverForToken(receiver.comName, token, RECEIVE_TIMEOUT_MS);
                    if (foundSenderNumber != null) {
                        log.info("Đã resolve: CCID={} com={} => phoneNumber={}", s.ccid, s.comName, foundSenderNumber);
                        // cập nhật vào DB
                        Sim dbSim = dbMap.getOrDefault(s.ccid, Sim.builder().ccid(s.ccid).deviceName(deviceName).comName(s.comName).build());
                        dbSim.setPhoneNumber(foundSenderNumber);
                        dbSim.setStatus("active");
                        dbSim.setLastUpdated(Instant.now());
                        simRepository.save(dbSim);
                        resolved = true;
                        break;
                    } else {
                        log.info("Không thấy SMS chứa token={} trên receiver {} (attempt {}). Thử receiver tiếp.", token, receiver.comName, attempt);
                    }
                } // attempts

                if (resolved) break; // next sender
            } // receiver loop

            if (!resolved) {
                log.warn("Không resolve được phoneNumber cho CCID={} com={}", s.ccid, s.comName);
                // vẫn lưu/update record nếu cần (phoneNumber null) và giữ status active
                Sim dbSim = dbMap.getOrDefault(s.ccid, Sim.builder().ccid(s.ccid).deviceName(deviceName).comName(s.comName).build());
                dbSim.setStatus("active");
                dbSim.setLastUpdated(Instant.now());
                simRepository.save(dbSim);
            }
        } // scanned loop

        // 6. Mark replaced: DB ccids not present in current scan => replaced
        Set<String> scannedCcids = scanned.stream().map(ss -> ss.ccid).filter(Objects::nonNull).collect(Collectors.toSet());
        simRepository.findByDeviceName(deviceName).stream()
                .filter(db -> db.getCcid() != null && !scannedCcids.contains(db.getCcid()))
                .forEach(db -> {
                    db.setStatus("replaced");
                    db.setLastUpdated(Instant.now());
                    simRepository.save(db);
                    log.info("Mark replaced CCID={} com={}", db.getCcid(), db.getComName());
                });

        log.info("Finish syncAndResolvePhoneNumbers for deviceName={}", deviceName);
    }

    // ---------- Helper types & methods ----------

    private static class ScannedSim {
        String comName;
        String ccid;
        String imsi;
        String phoneNumber;
        String simProvider;
    }

    /**
     * Scan tất cả COM -> trả về list ScannedSim (cố gắng lấy CCID, IMSI, phoneNumber via AT+CNUM)
     */
    private List<ScannedSim> scanAllPorts(String deviceName) {
        List<ScannedSim> scanned = new ArrayList<>();
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Found {} serial ports", ports.length);

        for (SerialPort port : ports) {
            String com = port.getSystemPortName();
            try (AtCommandHelper helper = new AtCommandHelper(port)) {
                // flush / basic check
                helper.sendAndRead("AT", 1000);

                String ccidRaw = helper.sendAndRead("AT+CCID", 1500);
                String ccid = extractCcid(ccidRaw);

                String imsi = safeRead(helper, "AT+CIMI", 1200);
                String phone = safeReadCnum(helper);

                ScannedSim s = new ScannedSim();
                s.comName = com;
                s.ccid = ccid;
                s.imsi = imsi;
                s.phoneNumber = phone;
                s.simProvider = detectProvider(imsi);
                scanned.add(s);

                log.info("Scanned {} -> ccid={} imsi={} phone={}", com, ccid, imsi, phone);
            } catch (Exception ex) {
                log.warn("Error scanning port {}: {}", com, ex.getMessage());
            }
        }
        return scanned;
    }

    /** try AT+CNUM (text) */
    private String safeReadCnum(AtCommandHelper helper) {
        try {
            String r = helper.sendAndRead("AT+CNUM", 1200);
            return parsePhoneFromCnumResponse(r);
        } catch (Exception e) {
            return null;
        }
    }

    private String safeRead(AtCommandHelper helper, String cmd, int timeoutMs) {
        try {
            return helper.sendAndRead(cmd, timeoutMs);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractCcid(String ccidRaw) {
        if (ccidRaw == null) return null;
        // ví dụ: "+CCID: 8981100025977896009F"
        int idx = ccidRaw.indexOf("CCID");
        if (idx != -1) {
            String[] parts = ccidRaw.split(":");
            if (parts.length >= 2) return parts[1].trim().split("\\s+")[0];
        }
        // fallback: trả nguyên raw sau trim
        return ccidRaw.trim();
    }

    private String parsePhoneFromCnumResponse(String out) {
        if (out == null) return null;
        // ví dụ: +CNUM: "","84901234567",145,7,0,4
        int i = out.indexOf("+CNUM:");
        if (i >= 0) {
            String line = out.substring(i);
            int q1 = line.indexOf("\"");
            if (q1 >= 0) {
                int q2 = line.indexOf("\"", q1 + 1);
                int q3 = line.indexOf("\"", q2 + 1);
                int q4 = line.indexOf("\"", q3 + 1);
                // sometimes number is in second quoted segment
                String candidate = null;
                String[] parts = line.split(",");
                for (String p : parts) {
                    p = p.trim();
                    if (p.startsWith("\"+") || p.startsWith("\"0") || p.matches("\"?\\d+\"?")) {
                        p = p.replaceAll("\"", "");
                        if (p.matches("^\\+?\\d+$")) {
                            candidate = p;
                            break;
                        }
                    }
                }
                return candidate;
            }
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

    private void syncScannedToDb(String deviceName, List<ScannedSim> scanned) {
        for (ScannedSim ss : scanned) {
            if (ss.ccid == null || ss.ccid.isBlank()) continue;
            Optional<Sim> opt = simRepository.findByDeviceNameAndCcid(deviceName, ss.ccid);
            Sim sim;
            if (opt.isPresent()) {
                sim = opt.get();
                // update comName/lastUpdated/phone if found
                sim.setComName(ss.comName);
                if (ss.phoneNumber != null) sim.setPhoneNumber(ss.phoneNumber);
                sim.setLastUpdated(Instant.now());
                simRepository.save(sim);
            } else {
                sim = Sim.builder()
                        .ccid(ss.ccid)
                        .imsi(ss.imsi)
                        .comName(ss.comName)
                        .deviceName(deviceName)
                        .phoneNumber(ss.phoneNumber)
                        .status("active")
                        .lastUpdated(Instant.now())
                        .build();
                simRepository.save(sim);
            }
        }
    }

    private List<ScannedSim> selectReceivers(List<ScannedSim> scanned) {
        // ưu tiên those have phoneNumber
        return scanned.stream()
                .filter(s -> s.phoneNumber != null && !s.phoneNumber.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * Gửi SMS (text mode) từ port `fromCom` đến `toNumber` với nội dung = token.
     * Trả về true nếu gửi lệnh thành công (không đảm bảo delivered).
     */
    private boolean sendSmsFromPort(String fromCom, String toNumber, String token) {
        SerialPort port = SerialPort.getCommPort(fromCom);
        try (AtCommandHelper helper = new AtCommandHelper(port)) {
            helper.sendAndRead("AT", 500);
            helper.sendAndRead("AT+CMGF=1", 500); // text mode
            // set charset if necessary: AT+CSCS="GSM"
            helper.sendAndRead("AT+CSCS=\"GSM\"", 500);

            // AT+CMGS="RECIPIENT"
            String cmd = "AT+CMGS=\"" + toNumber + "\"";
            // send command and then message terminated by Ctrl+Z (0x1A)
            helper.sendAndRead(cmd, 1000);
            // some AtCommandHelper implementations allow sending message + ctrlZ in one go:
            helper.sendAndRead(token + (char) 26, 5000); // wait for +CMGS response
            String resp = helper.sendAndRead("", 200); // maybe empty read to collect response
            log.debug("sendSmsFromPort response: {}", resp);
            return true;
        } catch (Exception e) {
            log.warn("sendSmsFromPort error from={} to={} : {}", fromCom, toNumber, e.getMessage());
            return false;
        }
    }

    /**
     * Poll inbox on receiverComName, looking for a NEW message that contains token.
     * Nếu tìm thấy -> trả về số của sender (nguồn SMS).
     */
    private String pollReceiverForToken(String receiverComName, String token, long timeoutMs) {
        SerialPort port = SerialPort.getCommPort(receiverComName);
        long start = System.currentTimeMillis();
        try (AtCommandHelper helper = new AtCommandHelper(port)) {
            helper.sendAndRead("AT", 300);
            helper.sendAndRead("AT+CMGF=1", 300); // text mode

            while (System.currentTimeMillis() - start < timeoutMs) {
                try {
                    // đọc unread messages
                    String inbox = helper.sendAndRead("AT+CMGL=\"REC UNREAD\"", 2000);
                    if (inbox != null && inbox.contains(token)) {
                        // parse phone number from the message header
                        String found = parsePhoneFromCmgl(inbox, token);
                        if (found != null) {
                            // Optionally: delete the message to avoid duplicate handling
                            // Not implemented here
                            return found;
                        }
                    }
                } catch (Exception e) {
                    log.debug("pollReceiver read attempt failed: {}", e.getMessage());
                }
                Thread.sleep(RECEIVE_POLL_INTERVAL_MS);
            }
        } catch (Exception e) {
            log.warn("pollReceiver exception on {} : {}", receiverComName, e.getMessage());
        }
        return null;
    }

    /**
     * Parse phone from CMGL/CMGR style response that contains token
     */
    private String parsePhoneFromCmgl(String cmglOut, String token) {
        if (cmglOut == null) return null;
        // tìm dòng header: +CMGL: index,"REC UNREAD","+84901234567",,"date"
        String[] lines = cmglOut.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains(token)) {
                // số thường nằm ở header dòng trước (cmgl header)
                if (i - 1 >= 0) {
                    String header = lines[i - 1];
                    int firstQuote = header.indexOf("\"");
                    int secondQuote = header.indexOf("\"", firstQuote + 1);
                    int thirdQuote = header.indexOf("\"", secondQuote + 1);
                    int fourthQuote = header.indexOf("\"", thirdQuote + 1);
                    if (thirdQuote >= 0 && fourthQuote > thirdQuote) {
                        String number = header.substring(thirdQuote + 1, fourthQuote);
                        number = number.replaceAll("\"", "").trim();
                        if (number.matches("^\\+?\\d+$")) return number;
                    } else {
                        // fallback: detect +digit pattern
                        int plus = header.indexOf("+");
                        if (plus >= 0) {
                            String sub = header.substring(plus).replaceAll("[^+0-9]", "");
                            if (sub.matches("^\\+?\\d+$")) return sub;
                        }
                    }
                }
            }
        }
        return null;
    }
}
