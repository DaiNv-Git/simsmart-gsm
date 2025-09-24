package app.simsmartgsm.service;

import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
        // use semi-blocking or non-blocking depending on AtCommandHelper's internals
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
                // không return ngay, nhưng mark "No AT" và cố gọi các lệnh khác nhanh
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

            // If per-port time used up, return early
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

    // ---- Parsers và detect providers (giữ nguyên / tinh gọn) ----
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