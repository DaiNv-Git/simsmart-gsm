package app.simsmartgsm.service;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import app.simsmartgsm.repository.SimRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class PortScannerService {

    private static final Logger log = LoggerFactory.getLogger(PortScannerService.class);

    // ----- TUNABLE PARAMETERS -----
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors()); // parallel workers
    private static final int PER_PORT_TOTAL_TIMEOUT_MS = 6_000; // tổng thời gian (ms) cho 1 port -> giảm để finish nhanh
    private static final int CMD_TIMEOUT_SHORT_MS = 700;  // timeout cho các lệnh nhanh (AT, CSCS)
    private static final int CMD_TIMEOUT_MED_MS = 1200;   // timeout cho lệnh có thể lâu (CCID/CIMI/CNUM)
    private static final int CMD_RETRY = 1;               // retry tối đa (0 = no retry)
    private static final int BAUD_RATE = 115200;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    private final SimRepository simRepository;
    private final SmsSenderService smsSenderService;
    private final SmsSenderServiceImpl smsSenderServiceImpl;

    // verification phone number (a configured number where outgoing verification SMS will be received)
    private final String verificationNumber = "YOUR_VERIFICATION_NUMBER"; // TODO: inject from application.properties

    public PortScannerService(SimRepository simRepository, SmsSenderService smsSenderService, SmsSenderServiceImpl smsSenderServiceImpl) {
        this.simRepository = simRepository;
        this.smsSenderService = smsSenderService;
        this.smsSenderServiceImpl = smsSenderServiceImpl;
    }

    public List<PortInfo> scanAllPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Start scanning {} ports with pool size {}", ports.length, THREAD_POOL_SIZE);

        List<Future<PortInfo>> futures = new ArrayList<>(ports.length);
        for (SerialPort port : ports) {
            // submit per-port task
            futures.add(executor.submit(() -> scanSinglePortSafely(port)));
        }

        List<PortInfo> results = new ArrayList<>(ports.length);
        for (Future<PortInfo> f : futures) {
            try {
                // Bắt timeout cho từng future (an toàn, tránh chậm)
                PortInfo info = f.get(PER_PORT_TOTAL_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS);
                results.add(info);
            } catch (TimeoutException te) {
                log.warn("Port scan future timeout: {}", te.getMessage());
                f.cancel(true);
                results.add(new PortInfo("unknown", false, null, null, null, "Future timeout"));
            } catch (ExecutionException ee) {
                log.error("Execution exception during port scan: {}", ee.getMessage(), ee);
                results.add(new PortInfo("unknown", false, null, null, null, "Execution error"));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                results.add(new PortInfo("unknown", false, null, null, null, "Interrupted"));
            }
        }

        log.info("Scan complete. Found {} results", results.size());
        return results;
    }

    private PortInfo scanSinglePortSafely(SerialPort port) {
        String portName = port.getSystemPortName();
        try {
            return scanSinglePort(port);
        } catch (Exception e) {
            log.warn("Unhandled error scanning {}: {}", portName, e.getMessage(), e);
            try { port.closePort(); } catch (Exception ignored) {}
            return new PortInfo(portName, false, null, null, null, "Error: " + e.getMessage());
        }
    }

    private PortInfo scanSinglePort(SerialPort port) {
        String portName = port.getSystemPortName();
        port.setBaudRate(BAUD_RATE);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, CMD_TIMEOUT_MED_MS, CMD_TIMEOUT_MED_MS);

        if (!port.openPort()) {
            log.debug("Cannot open port {}", portName);
            return new PortInfo(portName, false, null, null, null, "Cannot open port");
        }

        long startMillis = System.currentTimeMillis();
        try {
            AtCommandHelper helper = new AtCommandHelper(port);

            // 1) Quick sanity check: AT
            String atResp = tryCmdWithTimeout(helper, "AT", CMD_TIMEOUT_SHORT_MS, CMD_RETRY);
            if (atResp == null || atResp.isEmpty()) {
                log.debug("{}: no AT response", portName);
            }

            // 2) Set charset (non-critical)
            tryCmdWithTimeout(helper, "AT+CSCS=\"GSM\"", CMD_TIMEOUT_SHORT_MS, 0);

            // 3) CCID
            String ccid = tryCmdWithTimeout(helper, "AT+CCID", CMD_TIMEOUT_MED_MS, CMD_RETRY);
            ccid = parseCcid(ccid);

            // 4) IMSI (CIMI)
            String imsi = tryCmdWithTimeout(helper, "AT+CIMI", CMD_TIMEOUT_MED_MS, CMD_RETRY);
            imsi = parseImsi(imsi);

            // 5) CNUM
            String cnumResp = tryCmdWithTimeout(helper, "AT+CNUM", CMD_TIMEOUT_MED_MS, CMD_RETRY);
            String phone = parsePhoneNumberFromCnum(cnumResp);

            String provider = detectProvider(imsi);

            boolean ok = (ccid != null) || (imsi != null) || (phone != null);
            String msg;
            if (!ok) msg = "No data";
            else if (ccid != null && imsi != null) msg = "OK";
            else msg = "Partial";

            // If phone discovered -> persist/update DB
            if (phone != null) {
                saveOrUpdateSim(ccid, imsi, portName, provider, phone);
            } else {
                // phone == null: fallback logic
                // 1) Check if sim with this ccid/imsi exists in DB already
                boolean exists = false;
                if (ccid != null) {
                    exists = simRepository.findFirstByCcid(ccid).isPresent();
                }
                if (!exists && imsi != null) {
                    exists = simRepository.findFirstByImsi(imsi).isPresent();
                }

                if (!exists) {
                    // Not known in DB -> attempt to trigger verification SMS from this SIM
                    try {
                        String token = "VERIFY-" + UUID.randomUUID().toString().substring(0, 8);
                        String message = "SIM_VERIF:" + token; // unique token for inbound matching

                        // send SMS from this port to verificationNumber
                        boolean sendOk = smsSenderServiceImpl.sendSmsFromPort(port, verificationNumber, message);
                        if (sendOk) {
                            msg = msg + " (verification-sent)";
                            // Save as pending in DB so inbound processor can match later
                            Sim s = Sim.builder()
                                    .ccid(ccid)
                                    .comName(portName)
                                    .deviceName("local") // TODO set real deviceName
                                    .simProvider(provider)
                                    .phoneNumber(null)
                                    .status("PENDING_VERIFICATION")
                                    .content(token) // store token so incoming SMS processor can match by token
                                    .lastUpdated(new java.util.Date().toInstant())
                                    .build();
                            simRepository.save(s);
                            log.info("Saved pending verification sim ccid={} token={}", ccid, token);
                        } else {
                            msg = msg + " (verification-failed)";
                        }
                    } catch (Exception e) {
                        log.warn("Failed to send verification SMS from {}: {}", portName, e.getMessage(), e);
                        msg = msg + " (verification-exception)";
                    }
                } else {
                    msg = msg + " (exists-in-db)";
                }
            }

            long elapsed = System.currentTimeMillis() - startMillis;
            if (elapsed > PER_PORT_TOTAL_TIMEOUT_MS) {
                msg = msg + " (timed)";
            }

            return new PortInfo(portName, ok, provider, phone, ccid, msg);

        } catch (IOException | InterruptedException e) {
            log.warn("I/O error on {}: {}", portName, e.getMessage());
            return new PortInfo(portName, false, null, null, null, "IO: " + e.getMessage());
        } finally {
            try { port.closePort(); } catch (Exception ignore) {}
        }
    }

    private void saveOrUpdateSim(String ccid, String imsi, String portName, String provider, String phone) {
        Optional<Sim> byCcid = ccid == null ? Optional.empty() : simRepository.findFirstByCcid(ccid);
        if (byCcid.isPresent()) {
           Sim s = byCcid.get();
            s.setPhoneNumber(phone);
            s.setSimProvider(provider);
            s.setComName(portName);
            s.setStatus("active");
            s.setLastUpdated(new java.util.Date().toInstant());
            simRepository.save(s);
            log.info("Updated existing sim ccid={} phone={}", ccid, phone);
            return;
        }
        Optional<Sim> byImsi = imsi == null ? Optional.empty() : simRepository.findFirstByImsi(imsi);
        if (byImsi.isPresent()) {
            Sim s = byImsi.get();
            s.setPhoneNumber(phone);
            s.setSimProvider(provider);
            s.setComName(portName);
            s.setStatus("active");
            s.setLastUpdated(new java.util.Date().toInstant());
            simRepository.save(s);
            log.info("Updated existing sim imsi={} phone={}", imsi, phone);
            return;
        }

        // not found -> create
       Sim s =Sim.builder()
                .ccid(ccid)
                .comName(portName)
                .deviceName("local")
                .simProvider(provider)
                .phoneNumber(phone)
                .status("active")
                .lastUpdated(new java.util.Date().toInstant())
                .build();
        simRepository.save(s);
        log.info("Saved new sim ccid={} phone={}", ccid, phone);
    }

    /**
     * Helper: gọi AtCommandHelper.sendCommand nhưng giới hạn retry and return raw response (or null)
     */
    private String tryCmdWithTimeout(AtCommandHelper helper, String cmd, int timeoutMs, int retry) throws IOException, InterruptedException {
        IOException lastIo = null;
        for (int i = 0; i <= retry; i++) {
            try {
                // Gọi AtCommandHelper - giả sử signature sendCommand(cmd, timeoutMs, retryCount)
                String resp = helper.sendCommand(cmd, timeoutMs, 0);
                if (resp != null && !resp.isEmpty()) return resp.trim();
            } catch (IOException ioe) {
                lastIo = ioe;
                log.debug("IO exception for cmd {} attempt {}: {}", cmd, i, ioe.getMessage());
            }
            // nhỏ delay giữa retry
            Thread.sleep(60);
        }
        if (lastIo != null) throw lastIo;
        return null;
    }

    // Parsers and detectProvider (unchanged)...

    private String parsePhoneNumberFromCnum(String response) {
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
