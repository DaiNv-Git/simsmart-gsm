package app.simsmartgsm.service;


import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GsmSimScannerStandalone
 * - Standalone runner with main()
 * - Scans all COM ports, reads ICCID/IMSI and tries multiple fallbacks for MSISDN.
 */
public class GsmSimScannerStandalone {

    private static final int BAUD_RATE = 115200;

    public static void main(String[] args) {
        GsmSimScannerStandalone runner = new GsmSimScannerStandalone();
        System.out.println("Available COM ports:");
        for (SerialPort p : SerialPort.getCommPorts()) System.out.println(" - " + p.getSystemPortName());

        System.out.println("\nStart scanning...");
        List<ScanResult> results = runner.scanAllPorts();

        System.out.println("\n=== SCAN RESULTS ===");
        System.out.printf("%-8s %-22s %-16s %-14s %-18s %s%n",
                "PORT", "ICCID", "IMSI", "PHONE", "PROVIDER", "MSG");
        System.out.println("----------------------------------------------------------------------------------------");
        for (ScanResult r : results) {
            System.out.printf("%-8s %-22s %-16s %-14s %-18s %s%n",
                    r.portName,
                    r.iccid == null ? "<NONE>" : r.iccid,
                    r.imsi == null ? "<NONE>" : r.imsi,
                    r.phone == null ? "<UNKNOWN>" : r.phone,
                    r.provider == null ? "<UNK>" : r.provider,
                    r.message == null ? "" : r.message
            );
        }
        System.out.println("=== DONE ===");
    }

    public List<ScanResult> scanAllPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        List<ScanResult> list = new ArrayList<>(ports.length);
        for (SerialPort port : ports) {
            ScanResult res;
            try {
                res = scanSinglePort(port);
            } catch (Exception e) {
                res = new ScanResult(port.getSystemPortName(), null, null, null, null, "ERROR: " + e.getMessage(), false);
            }
            list.add(res);
        }
        return list;
    }

    private ScanResult scanSinglePort(SerialPort port) throws IOException, InterruptedException {
        String portName = port.getSystemPortName();
        port.setBaudRate(BAUD_RATE);
        // semi-blocking read
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);

        if (!port.openPort()) {
            return new ScanResult(portName, null, null, null, null, "Cannot open port", false);
        }

        try (AtCommandHelper h = new AtCommandHelper(port)) {
            // basic AT check
            String at = safeSend(h, "AT", 1000);
            if (at == null || !at.toUpperCase().contains("OK")) {
                return new ScanResult(portName, null, null, null, null, "No AT/OK", false);
            }

            // CCID
            String ccidResp = safeSend(h, "AT+CCID", 2000);
            String iccid = parseCcid(ccidResp);

            // IMSI
            String imsiResp = safeSend(h, "AT+CIMI", 2000);
            String imsi = parseImsi(imsiResp);

            // Try MSISDN fallbacks
            String phone = tryGetMsisdn(h);

            String provider = detectProvider(imsi);
            String msg = (iccid != null || imsi != null) ? "OK" : "No Data";
            if (phone == null) phone = null; // will be printed as <UNKNOWN>

            return new ScanResult(portName, iccid, imsi, phone, provider, msg, true);
        } finally {
            try { port.closePort(); } catch (Exception ignored) {}
        }
    }

    // ---------- fallback chain ----------
    private String tryGetMsisdn(AtCommandHelper h) throws IOException, InterruptedException {
        // 1) AT+CNUM
        String cnum = safeSend(h, "AT+CNUM", 1500);
        String phone = extractPhone(cnum);
        if (phone != null) return phone;

        // 2) phonebook: try storages SM, ME
        String[] stores = {"SM", "ME", "FD"};
        for (String st : stores) {
            safeSend(h, "AT+CPBS=\"" + st + "\"", 800);
            // try small indices 1..10 to be friendly to modems
            for (int idx = 1; idx <= 10; idx++) {
                String cpbr = safeSend(h, "AT+CPBR=" + idx + "," + idx, 1200);
                phone = extractPhone(cpbr);
                if (phone != null) return phone;
            }
        }

        // 3) CRSM read EF_MSISDN (0x6F40) records 1..3 lengths 13/32
        int[] p3lens = {13, 32};
        for (int len : p3lens) {
            for (int rec = 1; rec <= 3; rec++) {
                String crsmCmd = String.format("AT+CRSM=176,28480,%d,0,%d", rec, len);
                String crsm = safeSend(h, crsmCmd, 2500);
                String parsed = parseEfMsisdnFromCrsm(crsm);
                if (parsed != null) return parsed;
            }
        }

        // no msisdn found
        return null;
    }

    // ---------- helper send that returns null if nothing ----------
    private String safeSend(AtCommandHelper h, String cmd, int timeoutMs) {
        try {
            String resp = h.sendCommand(cmd, timeoutMs, 0);
            return (resp == null || resp.trim().isEmpty()) ? null : resp;
        } catch (IOException | InterruptedException ex) {
            return null;
        }
    }

    // ---------- parsers ----------
    private String parseCcid(String resp) {
        if (resp == null) return null;
        // try +CCID: ... or digits long
        Matcher m = Pattern.compile("\\+CCID: ?([0-9A-Fa-f]+)").matcher(resp);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("([0-9]{18,22})").matcher(resp);
        return m.find() ? m.group(1) : null;
    }

    private String parseImsi(String resp) {
        if (resp == null) return null;
        Matcher m = Pattern.compile("(\\d{14,16})").matcher(resp);
        return m.find() ? m.group(1) : null;
    }

    private String extractPhone(String resp) {
        if (resp == null) return null;
        // look for quoted phone or simple number
        Matcher m = Pattern.compile("\"(\\+?\\d{7,15})\"").matcher(resp);
        if (m.find()) return m.group(1);
        m = Pattern.compile("(\\+?\\d{7,15})").matcher(resp);
        return m.find() ? m.group(1) : null;
    }

    private String parseEfMsisdnFromCrsm(String resp) {
        if (resp == null) return null;
        // common patterns: +CRSM: 144,0,"0891F2..." or contains hex string
        Matcher m = Pattern.compile("\"([0-9A-Fa-f]+)\"").matcher(resp);
        if (m.find()) {
            String hex = m.group(1);
            return decodeBcdPhoneFromHex(hex);
        }
        // fallback: find hex block
        m = Pattern.compile("([0-9A-Fa-f]{8,})").matcher(resp.replaceAll("\\s+", ""));
        if (m.find()) {
            String hex = m.group(1);
            return decodeBcdPhoneFromHex(hex);
        }
        return null;
    }

    private String decodeBcdPhoneFromHex(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        hex = hex.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() % 2 != 0) hex = "0" + hex;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            int b = Integer.parseInt(hex.substring(i, i + 2), 16) & 0xFF;
            int low = b & 0x0F;
            int high = (b >> 4) & 0x0F;
            // low nibble usually first in SIM BCD
            if (low <= 9) sb.append(low);
            else if (low == 0x0F) break;
            if (high <= 9) sb.append(high);
            else if (high == 0x0F) break;
        }
        String s = sb.toString().replaceAll("^0+", "");
        return s.length() >= 7 ? s : null;
    }

    private String detectProvider(String imsi) {
        if (imsi == null) return null;
        if (imsi.startsWith("44011")) return "Rakuten Mobile (JP)";
        if (imsi.startsWith("44010")) return "NTT Docomo (JP)";
        if (imsi.startsWith("44020")) return "SoftBank (JP)";
        if (imsi.startsWith("44050")) return "KDDI au (JP)";
        if (imsi.startsWith("45201")) return "Mobifone (VN)";
        if (imsi.startsWith("45202")) return "Vinaphone (VN)";
        if (imsi.startsWith("45204")) return "Viettel (VN)";
        return "Unknown";
    }

    // ---------- simple DTO ----------
    static class ScanResult {
        final String portName;
        final String iccid;
        final String imsi;
        final String phone;
        final String provider;
        final String message;
        final boolean success;

        ScanResult(String portName, String iccid, String imsi, String phone, String provider, String message, boolean success) {
            this.portName = portName;
            this.iccid = iccid;
            this.imsi = imsi;
            this.phone = phone;
            this.provider = provider;
            this.message = message;
            this.success = success;
        }
    }
}
