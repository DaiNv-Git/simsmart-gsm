package app.simsmartgsm.service;

import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PortScannerService (reworked)
 *
 * - Nếu không thể lấy số bằng AT+CNUM thì:
 *    1) Thử đọc inbox (AT+CMGL) và decode PDU nếu cần
 *    2) Nếu vẫn null và có CollectorService configured -> gửi SMS test đến collector và chờ mapping result
 * - Có throttle/backoff, lastAttempt tracking, pending map (CompletableFuture)
 *
 * Note: Requires an external CollectorService bean that listens collector port(s) and calls
 *       collectorService.resolvePending(ccid, phone) when an SMS arrives from a sent test SMS.
 */
@Service
public class PortScannerService {

    private static final Logger log = LoggerFactory.getLogger(PortScannerService.class);

    // Tunables
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int PER_PORT_TOTAL_TIMEOUT_MS = 6_000;
    private static final int CMD_TIMEOUT_SHORT_MS = 700;
    private static final int CMD_TIMEOUT_MED_MS = 1200;
    private static final int CMD_RETRY = 1;
    private static final int BAUD_RATE = 115200;

    // Collector related
    private static final long COLLECTOR_WAIT_MS = 30_000L;      // chờ collector trả về số
    private static final long MIN_RETRY_INTERVAL_MS = 60_000L; // tối thiểu giữa 2 lần gửi SMS test cho cùng 1 SIM
    private static final int MAX_SMS_RETRIES = 2;              // retry gửi SMS test

    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    // pending map: ccid -> future(phone)
    private final ConcurrentMap<String, CompletableFuture<String>> pendingByCcid = new ConcurrentHashMap<>();
    // last attempt times to avoid spamming same sim
    private final ConcurrentMap<String, Long> lastAttemptAt = new ConcurrentHashMap<>();
    // last known phone for ccid
    private final ConcurrentMap<String, String> lastKnownPhone = new ConcurrentHashMap<>();

    // Optional external collector service (resolvePending must be called by Collector when SMS arrives)
    private final CollectorService collectorService; // may be null if not configured

    public PortScannerService(Optional<CollectorService> collectorServiceOpt) {
        this.collectorService = collectorServiceOpt.orElse(null);
    }

    public List<PortInfo> scanAllPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Start scanning {} ports with pool size {}", ports.length, THREAD_POOL_SIZE);

        List<Future<PortInfo>> futures = new ArrayList<>(ports.length);
        for (SerialPort port : ports) {
            futures.add(executor.submit(() -> scanSinglePortSafely(port)));
        }

        List<PortInfo> results = new ArrayList<>(ports.length);
        for (Future<PortInfo> f : futures) {
            try {
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

            // 1) Basic AT
            String atResp = tryCmdWithTimeout(helper, "AT", CMD_TIMEOUT_SHORT_MS, CMD_RETRY);
            if (atResp == null || atResp.isEmpty()) {
                log.debug("{}: no AT response", portName);
            }

            // 2) Set text mode preference (but we'll handle PDU too)
            tryCmdWithTimeout(helper, "AT+CMGF=1", CMD_TIMEOUT_SHORT_MS, 0); // text mode
            tryCmdWithTimeout(helper, "AT+CSCS=\"GSM\"", CMD_TIMEOUT_SHORT_MS, 0);

            // 3) CCID
            String ccidResp = tryCmdWithTimeout(helper, "AT+CCID", CMD_TIMEOUT_MED_MS, CMD_RETRY);
            String ccid = parseCcid(ccidResp);
            if (ccid == null) ccid = "unknown-" + portName;

            // 4) IMSI
            String imsiResp = tryCmdWithTimeout(helper, "AT+CIMI", CMD_TIMEOUT_MED_MS, CMD_RETRY);
            String imsi = parseImsi(imsiResp);

            // 5) Try CNUM
            String cnumResp = tryCmdWithTimeout(helper, "AT+CNUM", CMD_TIMEOUT_MED_MS, CMD_RETRY);
            String phone = parsePhoneNumberFromCnum(cnumResp);

            // 6) If CNUM null -> try reading inbox (some SIMs store msisdn in special messages)
            if (phone == null) {
                // try read all messages (may be PDU or text)
                String cmgl = tryCmdWithTimeout(helper, "AT+CMGL=\"ALL\"", CMD_TIMEOUT_MED_MS, 0);
                if (cmgl != null && !cmgl.isEmpty()) {
                    // parse +CMT: lines
                    phone = parsePhoneFromSms(cmgl);
                    if (phone == null) {
                        // try detect PDU blocks and decode
                        String pduPhone = parsePhoneFromPduList(cmgl);
                        if (pduPhone != null) phone = pduPhone;
                    }
                }
            }

            // 7) If still null -> send SMS test to collector (if configured) with pending mechanism
            if (phone == null && collectorService != null) {
                // don't spam the same SIM: check lastAttemptAt
                long now = System.currentTimeMillis();
                Long lastAt = lastAttemptAt.getOrDefault(ccid, 0L);
                if (now - lastAt >= MIN_RETRY_INTERVAL_MS) {
                    lastAttemptAt.put(ccid, now);

                    // create pending future
                    CompletableFuture<String> pending = new CompletableFuture<>();
                    pendingByCcid.put(ccid, pending);

                    String collectorNumber = collectorService.getCollectorNumber();
                    if (collectorNumber != null) {
                        boolean sent = false;
                        AtomicInteger sentCount = new AtomicInteger(0);
                        for (int attempt = 0; attempt <= MAX_SMS_RETRIES; attempt++) {
                            try {
                                if (!isNetworkReady(helper)) {
                                    log.debug("{}: network not ready, skipping send attempt", portName);
                                    break;
                                }
                                // set text mode then send
                                tryCmdWithTimeout(helper, "AT+CMGF=1", CMD_TIMEOUT_SHORT_MS, 0);
                                tryCmdWithTimeout(helper, "AT+CMGS=\"" + collectorNumber + "\"", CMD_TIMEOUT_SHORT_MS, 0);
                                // use helper.sendRaw to send message body + Ctrl+Z, assume helper has this method
                                helper.sendRaw("SIMTEST:" + Optional.ofNullable(imsi).orElse(ccid) + (char)26, CMD_TIMEOUT_MED_MS);
                                sent = true;
                                sentCount.incrementAndGet();
                                log.info("{}: sent test SMS to collector {} (attempt {})", portName, collectorNumber, attempt + 1);
                                // give some small pause for operator/network
                                Thread.sleep(500);
                            } catch (Exception e) {
                                log.warn("{}: failed to send test SMS attempt {} => {}", portName, attempt + 1, e.getMessage());
                            }
                            if (sent) break;
                        }
                        if (sent) {
                            try {
                                // wait for collector to resolve pending
                                String resolvedPhone = pending.get(COLLECTOR_WAIT_MS, TimeUnit.MILLISECONDS);
                                if (resolvedPhone != null && !resolvedPhone.isEmpty()) {
                                    phone = resolvedPhone;
                                    lastKnownPhone.put(ccid, phone);
                                    log.info("{}: resolved phone via collector: {}", portName, phone);
                                } else {
                                    log.debug("{}: collector returned null", portName);
                                }
                            } catch (TimeoutException te) {
                                log.warn("{}: waiting collector timed out", portName);
                            } catch (Exception ex) {
                                log.warn("{}: error while waiting collector result: {}", portName, ex.getMessage());
                            } finally {
                                pendingByCcid.remove(ccid);
                            }
                        } else {
                            log.debug("{}: could not send test SMS to collector (not sent)", portName);
                            pendingByCcid.remove(ccid);
                        }
                    } else {
                        log.debug("{}: collectorNumber is null", portName);
                        pendingByCcid.remove(ccid);
                    }
                } else {
                    log.debug("{}: skipping send test SMS due to backoff (lastAt {})", portName, lastAt);
                }
            }

            // 8) final checks
            String provider = detectProvider(imsi);
            boolean ok = (phone != null) || (ccid != null) || (imsi != null);
            String msg;
            if (!ok) msg = "No data";
            else if (ccid != null && imsi != null) msg = "OK";
            else msg = "Partial";

            long elapsed = System.currentTimeMillis() - startMillis;
            if (elapsed > PER_PORT_TOTAL_TIMEOUT_MS) msg = msg + " (timed)";

            return new PortInfo(portName, ok, provider, phone, ccid, msg);

        } catch (IOException | InterruptedException e) {
            log.warn("I/O error on {}: {}", portName, e.getMessage());
            return new PortInfo(portName, false, null, null, null, "IO: " + e.getMessage());
        } finally {
            try { port.closePort(); } catch (Exception ignore) {}
        }
    }

    /**
     * Called by CollectorService when it receives an SMS from a test message.
     * Collector should call this with the CCID (or IMEI/IMSI as key) and phone number of sender.
     */
    public void resolvePendingFromCollector(String ccid, String phone) {
        if (ccid == null) return;
        CompletableFuture<String> pending = pendingByCcid.get(ccid);
        if (pending != null && !pending.isDone()) {
            pending.complete(phone);
            pendingByCcid.remove(ccid);
            lastKnownPhone.put(ccid, phone);
            log.info("Resolved pending ccid {} -> {}", ccid, phone);
        } else {
            // no pending: still store last known
            lastKnownPhone.put(ccid, phone);
            log.info("No pending future for ccid {} but stored lastKnownPhone {}", ccid, phone);
        }
    }

    // Utility: try sending cmd with internal retries, return trimmed response or null
    private String tryCmdWithTimeout(AtCommandHelper helper, String cmd, int timeoutMs, int retry) throws IOException, InterruptedException {
        IOException lastIo = null;
        for (int i = 0; i <= retry; i++) {
            try {
                String resp = helper.sendCommand(cmd, timeoutMs, 0);
                if (resp != null && !resp.isEmpty()) return resp.trim();
            } catch (IOException ioe) {
                lastIo = ioe;
                log.debug("IO exception for cmd {} attempt {}: {}", cmd, i, ioe.getMessage());
            }
            Thread.sleep(60);
        }
        if (lastIo != null) throw lastIo;
        return null;
    }

    // Basic network ready checks: CPIN, CREG, CSQ
    private boolean isNetworkReady(AtCommandHelper helper) {
        try {
            String cpin = helper.sendCommand("AT+CPIN?", CMD_TIMEOUT_SHORT_MS, 0);
            if (cpin != null && cpin.contains("SIM PIN")) {
                return false;
            }
            String creg = helper.sendCommand("AT+CREG?", CMD_TIMEOUT_SHORT_MS, 0);
            if (creg != null && (creg.contains(",1") || creg.contains(",5"))) {
                // registered
            } else {
                log.debug("CREG not registered: {}", creg);
                return false;
            }
            String csq = helper.sendCommand("AT+CSQ", CMD_TIMEOUT_SHORT_MS, 0);
            if (csq != null && csq.contains(":")) {
                // +CSQ: <rssi>,<ber>
                String[] parts = csq.split(":");
                if (parts.length > 1) {
                    String[] vals = parts[1].trim().split(",");
                    int rssi = Integer.parseInt(vals[0].trim());
                    if (rssi == 99 || rssi < 2) {
                        log.debug("CSQ too low: {}", csq);
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.debug("Network check failed: {}", e.getMessage());
            return false;
        }
    }

    // ---------- Parsers ----------
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
        if (imsi.startsWith("44011")) return "Rakuten Mobile (JP)";
        if (imsi.startsWith("44020")) return "SoftBank (JP)";
        if (imsi.startsWith("44050")) return "KDDI au (JP)";
        if (imsi.startsWith("45201")) return "Mobifone (VN)";
        if (imsi.startsWith("45202")) return "Vinaphone (VN)";
        if (imsi.startsWith("45204")) return "Viettel (VN)";
        return "Unknown";
    }

    private String parsePhoneFromSms(String smsRaw) {
        if (smsRaw == null) return null;
        for (String line : smsRaw.split("\\r?\\n")) {
            if (line.contains("+CMT:") || line.contains("+CMGL:")) {
                int firstQuote = line.indexOf("\"");
                if (firstQuote >= 0) {
                    int secondQuote = line.indexOf("\"", firstQuote + 1);
                    if (secondQuote > firstQuote) {
                        String num = line.substring(firstQuote + 1, secondQuote);
                        if (num != null && !num.isEmpty()) return num;
                    }
                }
            }
        }
        return null;
    }

    // Try to parse PDU blocks for sender number (naive; better replace with mature PDU lib)
    private String parsePhoneFromPduList(String cmglRaw) {
        if (cmglRaw == null) return null;
        // naive: find hex blocks and try decode as UCS2 sender or extract OA from PDU header
        for (String line : cmglRaw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.matches("^[0-9A-Fa-f]+$") && t.length() > 20) {
                // try decode sender from PDU header (very naive)
                String possible = decodePduGetSender(t);
                if (possible != null) return possible;
            }
        }
        return null;
    }

    // Very naive PDU sender parser + UCS2 body decode (for simple cases)
    private String decodePduGetSender(String pduHex) {
        try {
            byte[] b = hexStringToByteArray(pduHex);
            if (b.length < 12) return null;
            // skip SMSC length byte
            int idx = 1 + (b[0] & 0xFF);
            if (idx >= b.length) return null;
            // TP-DA (destination address) parsing: OA length at idx+1 etc - this is heuristic
            int oaLen = b[idx + 1] & 0xFF;
            int toa = b[idx + 2] & 0xFF;
            int oaStart = idx + 3;
            int oaBytes = (oaLen + 1) / 2;
            if (oaStart + oaBytes > b.length) return null;
            byte[] oa = Arrays.copyOfRange(b, oaStart, oaStart + oaBytes);
            String semi = semiOctetsToNumber(oa, oaLen);
            if (semi != null && !semi.isEmpty()) return semi;
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static String semiOctetsToNumber(byte[] data, int numDigits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            int low = v & 0x0F;
            int high = (v >> 4) & 0x0F;
            sb.append(low == 0x0F ? "" : Integer.toString(low));
            if (sb.length() < numDigits) sb.append(high == 0x0F ? "" : Integer.toString(high));
        }
        String res = sb.toString();
        return res.length() > numDigits ? res.substring(0, numDigits) : res;
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
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

    // ---- CollectorService interface (simple contract) ----
    // Implement this bean elsewhere: it must listen on collector port(s), extract sender number from incoming SMS
    // and call portScannerService.resolvePendingFromCollector(ccid, phone).
    public interface CollectorService {
        /**
         * return a phone number (String) that scanner should send test SMS to,
         * e.g. "+84901234567"
         */
        String getCollectorNumber();
    }
}
