package app.simsmartgsm.service;

import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

            // 5) CNUM -> primary attempt to get MSISDN
            String cnumResp = tryCmdWithTimeout(helper, "AT+CNUM", CMD_TIMEOUT_MED_MS, CMD_RETRY);
            String phone = parsePhoneNumberFromCnum(cnumResp);

            // --- NEW: if phone null, try phonebook (CPBR) and then EF_MSISDN via CRSM ---
            if (phone == null) {
                try {
                    phone = readMsisdnFromPhonebook(helper);
                    if (phone == null) {
                        phone = readMsisdnFromEfMsisdnViaCrsm(helper);
                    }
                } catch (Exception e) {
                    log.debug("{}: fallback MSISDN read error: {}", portName, e.getMessage());
                }
            }

            String provider = detectProvider(imsi);

            boolean ok = (ccid != null) || (imsi != null) || (phone != null);
            String msg;
            if (!ok) msg = "No data";
            else if (ccid != null && imsi != null) msg = "OK";
            else msg = "Partial";

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

    // --- NEW helper: đọc bằng AT+CPBS / AT+CPBR ---
    private String readMsisdnFromPhonebook(AtCommandHelper helper) throws IOException, InterruptedException {
        // chọn bộ nhớ SIM
        tryCmdWithTimeout(helper, "AT+CPBS=\"SM\"", CMD_TIMEOUT_SHORT_MS, 0);
        // lấy range (vài SIM ghi 1..250, nên ta gọi 1..250) - có thể điều chỉnh
        String resp = tryCmdWithTimeout(helper, "AT+CPBR=1,250", CMD_TIMEOUT_MED_MS, 0);
        if (resp == null) return null;

        // +CPBR: <index>,"<number>",<type>,"<name>"
        // hoặc dòng numbers only
        Pattern p = Pattern.compile("\\+CPBR:\\s*\\d+,\"([^\"]+)\",?\\d*,?\"?([^\"\\r\\n]*)?\"?", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(resp);
        while (m.find()) {
            String number = m.group(1);
            if (number != null && number.matches("\\+?\\d{7,15}")) {
                return number;
            }
        }

        // fallback: tìm chuỗi số trong toàn bộ response
        Matcher m2 = Pattern.compile("(\\+?\\d{7,15})").matcher(resp);
        if (m2.find()) return m2.group(1);

        return null;
    }

    // --- NEW helper: đọc EF_MSISDN qua AT+CRSM (READ RECORD EF 6F40) ---
    private String readMsisdnFromEfMsisdnViaCrsm(AtCommandHelper helper) throws IOException, InterruptedException {
        // Một lần gọi CRSM để đọc file EF_MSISDN (file id 6F40), record 1..5 thử lần lượt
        // CRSM format: AT+CRSM=<command>,<fileid>,<p1>,<p2>,<p3>
        // READ RECORD (command=176). p1=record number, p2=0, p3=len (13 typical)
        for (int rec = 1; rec <= 5; rec++) {
            String cmd = String.format("AT+CRSM=176,28480,%d,0,13", rec); // 28480 decimal = 0x6F40
            String resp = tryCmdWithTimeout(helper, cmd, CMD_TIMEOUT_MED_MS, 0);
            if (resp == null) continue;
            // tìm +CRSM: ... "HEXSTRING"
            Pattern p = Pattern.compile("\\+CRSM:\\s*\\d+,\\d+,?\"?([0-9A-Fa-f]+)\"?");
            Matcher m = p.matcher(resp);
            if (m.find()) {
                String hex = m.group(1);
                String num = parseEfMsisdnHex(hex);
                if (num != null) return num;
            } else {
                // một số module trả về: +CRSM: 144,0,"0891F2..." hoặc chứa "0891F2..."
                Matcher m2 = Pattern.compile("\"([0-9A-Fa-f]+)\"").matcher(resp);
                if (m2.find()) {
                    String hex = m2.group(1);
                    String num = parseEfMsisdnHex(hex);
                    if (num != null) return num;
                }
            }
        }
        return null;
    }

    // --- Parser cho EF_MSISDN hex (BCD) ---
// Cách đơn giản: chuyển hex->bytes, tìm phần chứa BCD số (bypass các header bytes nếu có), rồi decode BCD nibbles
    private String parseEfMsisdnHex(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        try {
            hex = hex.replaceAll("[^0-9A-Fa-f]", "");
            if (hex.length() % 2 != 0) hex = "0" + hex;
            int len = hex.length() / 2;
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) {
                data[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }

            // heuristic: try to find BCD-looking region: pick bytes and decode until F/NIL
            // many cards store MSISDN: [TON/NPI][BCD digits...][padding F]
            for (int start = 0; start < Math.min(data.length, 4); start++) {
                String digits = bcdToDigits(data, start, data.length - start);
                if (digits != null && digits.length() >= 7 && digits.length() <= 15) {
                    // cleanup possible leading '0' / '91' country prefix handling
                    digits = digits.replaceAll("^\\D+", "");
                    return digits;
                }
            }
            // as fallback try whole array
            String maybe = bcdToDigits(data, 0, data.length);
            if (maybe != null && maybe.length() >= 7) return maybe;
        } catch (Exception ex) {
            log.debug("parseEfMsisdnHex error: {}", ex.getMessage());
        }
        return null;
    }

    // convert BCD bytes to digit string, stop at 0xF nibble
    private String bcdToDigits(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length && i < data.length; i++) {
            int b = data[i] & 0xFF;
            int low = b & 0x0F;
            int high = (b >> 4) & 0x0F;
            // low nibble first in many SIM encodings
            if (low <= 9) sb.append(low);
            else if (low == 0x0F) break;
            if (high <= 9) sb.append(high);
            else if (high == 0x0F) break;
        }
        String s = sb.toString();
        // normalize: remove leading padding 'F's or non-digit
        s = s.replaceAll("[^0-9+]", "");
        // some EF store with leading '91' for international; keep as-is
        if (s.isEmpty()) return null;
        return s;
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
        if (imsi.startsWith("44011")) return "Rakuten Mobile (JP)";
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