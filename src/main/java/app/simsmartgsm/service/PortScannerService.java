package app.simsmartgsm.service;

import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.service.impl.SmsSenderServiceImpl;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Service
public class PortScannerService {
    private static final Logger log = LoggerFactory.getLogger(PortScannerService.class);

    // Tuning
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int PER_PORT_TOTAL_TIMEOUT_MS = 6_000;
    private static final int CMD_TIMEOUT_SHORT_MS = 700;
    private static final int CMD_TIMEOUT_MED_MS = 1200;
    private static final int CMD_RETRY = 1;
    private static final int BAUD_RATE = 115200;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private final SmsSenderService smsSenderService;
    private final app.simsmartgsm.service.impl.SmsSenderServiceImpl smsSenderServiceImpl;
    private final SimRepository simRepository;

    public PortScannerService(SmsSenderService smsSenderService, SmsSenderServiceImpl smsSenderServiceImpl, SimRepository simRepository) {
        this.smsSenderService = smsSenderService;
        this.smsSenderServiceImpl = smsSenderServiceImpl;
        this.simRepository = simRepository;
    }

    /** Scan tất cả COM, trả về danh sách PortInfo. */
    public List<PortInfo> scanAllPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Scanning {} ports with pool {}", ports.length, THREAD_POOL_SIZE);

        List<Future<PortInfo>> futures = new ArrayList<>(ports.length);
        for (SerialPort p : ports) futures.add(executor.submit(() -> scanSinglePortSafely(p)));

        List<PortInfo> results = new ArrayList<>(ports.length);
        for (Future<PortInfo> f : futures) {
            try {
                results.add(f.get(PER_PORT_TOTAL_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS));
            } catch (TimeoutException te) {
                log.warn("Port scan future timeout: {}", te.getMessage());
                f.cancel(true);
                results.add(new PortInfo("unknown", false, null, null, null, "Future timeout"));
            } catch (ExecutionException ee) {
                log.error("Execution error: {}", ee.getMessage(), ee);
                results.add(new PortInfo("unknown", false, null, null, null, "Execution error"));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                results.add(new PortInfo("unknown", false, null, null, null, "Interrupted"));
            }
        }
        log.info("Scan done. {} results", results.size());
        return results;
    }

    private PortInfo scanSinglePortSafely(SerialPort port) {
        try {
            return scanSinglePort(port);
        } catch (Exception e) {
            String name = port.getSystemPortName();
            log.warn("Unhandled error {}: {}", name, e.getMessage(), e);
            try { port.closePort(); } catch (Exception ignored) {}
            return new PortInfo(name, false, null, null, null, "Error: " + e.getMessage());
        }
    }

    private PortInfo scanSinglePort(SerialPort port) {
        final String portName = port.getSystemPortName();
        port.setBaudRate(BAUD_RATE);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, CMD_TIMEOUT_MED_MS, CMD_TIMEOUT_MED_MS);

        if (!port.openPort()) {
            log.debug("Cannot open {}", portName);
            return new PortInfo(portName, false, null, null, null, "Cannot open port");
        }

        long start = System.currentTimeMillis();
        try (AtCommandHelper helper = new AtCommandHelper(port)) {

            // 1) Sanity & basic setup
            safeSend(helper, "AT", CMD_TIMEOUT_SHORT_MS);
            safeSend(helper, "AT+CSCS=\"GSM\"", CMD_TIMEOUT_SHORT_MS);

            // 2) Read CCID / IMSI / CNUM
            String ccid = parseCcid(safeSend(helper, "AT+CCID", CMD_TIMEOUT_MED_MS));
            String imsi = parseImsi(safeSend(helper, "AT+CIMI", CMD_TIMEOUT_MED_MS));
            String cnum = safeSend(helper, "AT+CNUM", CMD_TIMEOUT_MED_MS);
            String phone = parsePhoneFromCnum(cnum);
            String provider = detectProvider(imsi);

            boolean ok = Stream.of(ccid, imsi, phone).anyMatch(Objects::nonNull);
            String msg = !ok ? "No data" : (ccid != null && imsi != null ? "OK" : "Partial");

            if (phone != null) {
                saveOrUpdateSim(ccid, imsi, portName, provider, phone, "active", null);
            } else {
                // Không đọc được số
                boolean exists = existsInDb(ccid, imsi);
                if (!exists) {
                    // Chọn một SIM đang rảnh bằng AT quét tại máy
                    ReceiverCandidate receiver = pickFreeSimByAtScan().orElse(null);
                    if (receiver == null || receiver.phoneNumber == null) {
                        msg += " (no-free-sim)";
                    } else {
                        // Gửi SMS xác thực từ SIM này sang SIM receiver
                        String token = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                        String body = "SIM_VERIF:" + token;

                        boolean sent = smsSenderServiceImpl.sendSmsFromPort(port, receiver.phoneNumber, body);
                        if (sent) {
                            msg += " (verification-sent -> " + receiver.phoneNumber + ")";
                            // Lưu bản ghi pending
                            saveOrUpdateSim(ccid, imsi, portName, provider, null, "PENDING_VERIFICATION", token);
                        } else {
                            msg += " (verification-failed)";
                        }
                    }
                } else {
                    msg += " (exists-in-db)";
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > PER_PORT_TOTAL_TIMEOUT_MS) msg += " (timed)";
            return new PortInfo(portName, ok, provider, phone, ccid, msg);

        } catch (IOException e) {
            log.warn("I/O {}: {}", portName, e.getMessage());
            return new PortInfo(portName, false, null, null, null, "IO: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PortInfo(portName, false, null, null, null, "Interrupted");
        } finally {
            try { port.closePort(); } catch (Exception ignore) {}
        }
    }

    /* =================== AT-based free SIM selection =================== */

    public static class ReceiverCandidate {
        public final String portName;
        public final String phoneNumber;
        public final String ccid;
        public final String rawInfo;

        public ReceiverCandidate(String portName, String phoneNumber, String ccid, String rawInfo) {
            this.portName = portName; this.phoneNumber = phoneNumber; this.ccid = ccid; this.rawInfo = rawInfo;
        }

        @Override public String toString() {
            return "ReceiverCandidate{port=" + portName + ", phone=" + phoneNumber + ", ccid=" + ccid + "}";
        }
    }

    /** Quét các cổng và chọn 1 SIM "rảnh" (CPIN=READY, CREG=1/5, CPAS=0, CSQ>=8). */
    private Optional<ReceiverCandidate> pickFreeSimByAtScan() {
        SerialPort[] ports = SerialPort.getCommPorts();

        for (SerialPort p : ports) {
            String pn = p.getSystemPortName();
            try {
                p.setBaudRate(BAUD_RATE);
                p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, CMD_TIMEOUT_MED_MS, CMD_TIMEOUT_MED_MS);
                if (!p.openPort()) continue;

                try (AtCommandHelper helper = new AtCommandHelper(p)) {
                    String at = safeSend(helper, "AT", 500);
                    String cpin = safeSend(helper, "AT+CPIN?", 800);
                    if (cpin == null || !cpin.contains("READY")) continue;

                    String creg = safeSend(helper, "AT+CREG?", 800);
                    String stat = parseCreg(creg);
                    boolean registered = "1".equals(stat) || "5".equals(stat);
                    if (!registered) continue;

                    String cpas = safeSend(helper, "AT+CPAS", 700);
                    String st = parseCpas(cpas);
                    if (!"0".equals(st)) continue;

                    Integer csq = parseCsq(safeSend(helper, "AT+CSQ", 700));
                    if (csq == null || csq < 8) continue;

                    String ccid = parseCcid(safeSend(helper, "AT+CCID", 1200));
                    String phone = parsePhoneFromCnum(safeSend(helper, "AT+CNUM", 1200));

                    String info = String.format("AT:%s;CPIN:%s;CREG:%s;CPAS:%s;CSQ:%s;CCID:%s;CNUM:%s",
                            at, cpin, creg, cpas, csq, ccid, phone);
                    log.info("Receiver candidate on {} -> phone={}, csq={}", pn, phone, csq);
                    // Bật URC nếu bạn có listener inbound tại local (tùy chọn)
                    safeSend(helper, "AT+CNMI=2,1,0,0,0", 500);

                    if (phone != null) return Optional.of(new ReceiverCandidate(pn, phone, ccid, info));
                } finally {
                    try { p.closePort(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log.debug("pickFreeSimByAtScan {} failed: {}", pn, e.getMessage());
                try { p.closePort(); } catch (Exception ignored) {}
            }
        }
        return Optional.empty();
    }

    /* =================== DB helpers =================== */

    private boolean existsInDb(String ccid, String imsi) {
        if (ccid != null && simRepository.findFirstByCcid(ccid).isPresent()) return true;
        return imsi != null && simRepository.findFirstByImsi(imsi).isPresent();
    }

    private void saveOrUpdateSim(String ccid, String imsi, String com, String provider,
                                 String phone, String status, String token) {
        Date now = new Date();

        Optional<Sim> byCcid = ccid == null ? Optional.empty() : simRepository.findFirstByCcid(ccid);
        Optional<Sim> byImsi = Optional.empty();
        if (byCcid.isEmpty() && imsi != null) byImsi = simRepository.findFirstByImsi(imsi);

        Optional<Sim> finalByImsi = byImsi;
        Sim s = byCcid.orElseGet(() -> finalByImsi.orElseGet(Sim::new));

        if (s.getId() == null) {
            // new
            s.setCcid(ccid);
            s.setComName(com);
            s.setDeviceName("local");
            s.setSimProvider(provider);
            s.setPhoneNumber(phone);
            s.setStatus( "active");
            s.setContent(token);
            s.setLastUpdated(now.toInstant());
        } else {
            // update
            if (ccid != null) s.setCcid(ccid);
            if (provider != null) s.setSimProvider(provider);
            if (com != null) s.setComName(com);
            if (phone != null) s.setPhoneNumber(phone);
            if (status != null) s.setStatus(status);
            if (token != null) s.setContent(token);
            s.setLastUpdated(now.toInstant());
        }
        simRepository.save(s);
    }

    /* =================== Parsers =================== */

    private String safeSend(AtCommandHelper helper, String cmd, int timeoutMs) {
        try {
            String r = helper.sendCommand(cmd, timeoutMs, 0);
            return (r != null && !r.isEmpty()) ? r : null;
        } catch (Exception e) {
            log.debug("safeSend {} failed: {}", cmd, e.getMessage());
            return null;
        }
    }

    private String parsePhoneFromCnum(String response) {
        if (response == null || response.isEmpty()) return null;
        for (String line : response.split("\\r?\\n")) {
            if (line.contains("+CNUM")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) return parts[1].replace("\"", "").trim();
            }
            String t = line.trim();
            if (t.matches("\\+?\\d{7,15}")) return t;
        }
        return null;
    }

    private String parseCcid(String response) {
        if (response == null || response.isEmpty()) return null;
        for (String line : response.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("+CCID")) return l.replace("+CCID:", "").replace(" ", "").trim();
            if (l.matches("\\d{18,22}")) return l;
        }
        return null;
    }

    private String parseImsi(String response) {
        if (response == null || response.isEmpty()) return null;
        for (String line : response.split("\\r?\\n")) {
            String l = line.trim();
            if (l.matches("\\d{14,16}")) return l;
        }
        return null;
    }

    private String parseCreg(String cregResp) {
        if (cregResp == null) return null;
        for (String line : cregResp.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("+CREG:")) {
                String[] parts = line.replace("+CREG:", "").split(",");
                if (parts.length >= 2) return parts[1].trim();
                if (parts.length == 1) return parts[0].trim();
            }
        }
        return null;
    }

    private String parseCpas(String cpasResp) {
        if (cpasResp == null) return null;
        for (String line : cpasResp.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("+CPAS:")) {
                String[] parts = line.replace("+CPAS:", "").split(",");
                return parts[0].trim();
            }
            if (line.matches("^\\d$")) return line;
        }
        return null;
    }

    private Integer parseCsq(String csqResp) {
        if (csqResp == null) return null;
        for (String line : csqResp.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("+CSQ:")) {
                String[] parts = line.replace("+CSQ:", "").split(",");
                try {
                    int val = Integer.parseInt(parts[0].trim());
                    return (val == 99) ? -1 : val;
                } catch (Exception ignore) {}
            }
        }
        return null;
    }

    private String detectProvider(String imsi) {
        if (imsi == null) return null;
        if (imsi.startsWith("44010")) return "NTT Docomo (JP)";
        if (imsi.startsWith("44020")) return "SoftBank (JP)";
        if (imsi.startsWith("44050")) return "KDDI au (JP)";
        if (imsi.startsWith("45201")) return "Mobifone (VN)";
        if (imsi.startsWith("45202")) return "Vinaphone (VN)";
        if (imsi.startsWith("45204")) return "Viettel (VN)";
        return "Unknown";
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down PortScanner executor");
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
