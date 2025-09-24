package app.simsmartgsm.service;

import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PortScannerService {

    private static final Logger log = LoggerFactory.getLogger(PortScannerService.class);

    // ---- PARAMS ----
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int PER_PORT_TOTAL_TIMEOUT_MS = 6000;
    private static final int CMD_TIMEOUT_SHORT_MS = 700;
    private static final int CMD_TIMEOUT_MED_MS = 1500;
    private static final int CMD_RETRY = 1;
    private static final int BAUD_RATE = 115200;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    public List<PortInfo> scanAllPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Scanning {} ports", ports.length);

        List<Future<PortInfo>> futures = new ArrayList<>();
        for (SerialPort port : ports) {
            futures.add(executor.submit(() -> scanSinglePortSafely(port, ports)));
        }

        List<PortInfo> results = new ArrayList<>();
        for (Future<PortInfo> f : futures) {
            try {
                results.add(f.get(PER_PORT_TOTAL_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                log.warn("Port scan error: {}", e.getMessage());
                results.add(new PortInfo("unknown", false, null, null, null, "Error"));
            }
        }
        return results;
    }

    private PortInfo scanSinglePortSafely(SerialPort port, SerialPort[] allPorts) {
        try {
            return scanSinglePort(port, allPorts);
        } catch (Exception e) {
            log.warn("Scan failed {}: {}", port.getSystemPortName(), e.getMessage());
            return new PortInfo(port.getSystemPortName(), false, null, null, null, "Exception");
        }
    }

    private PortInfo scanSinglePort(SerialPort port, SerialPort[] allPorts) throws IOException, InterruptedException {
        String portName = port.getSystemPortName();
        port.setBaudRate(BAUD_RATE);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, CMD_TIMEOUT_MED_MS, CMD_TIMEOUT_MED_MS);

        if (!port.openPort()) {
            return new PortInfo(portName, false, null, null, null, "Cannot open");
        }

        try (AtCommandHelper helper = new AtCommandHelper(port)) {
            // CCID & IMSI
            String ccid = parseCcid(tryCmd(helper, "AT+CCID", CMD_TIMEOUT_MED_MS));
            String imsi = parseImsi(tryCmd(helper, "AT+CIMI", CMD_TIMEOUT_MED_MS));

            // Phone
            String phone = parsePhoneNumberFromCnum(tryCmd(helper, "AT+CNUM", CMD_TIMEOUT_MED_MS));
            if (phone == null) phone = tryPhonebook(helper);
            if (phone == null) phone = tryEfMsisdn(helper);

            // Fallback: send SMS to another COM
            if (phone == null) {
                phone = tryCrossPortSms(helper, portName, allPorts);
            }

            String provider = detectProvider(imsi);
            boolean ok = (ccid != null) || (imsi != null) || (phone != null);
            String msg = ok ? "OK" : "No data";

            return new PortInfo(portName, ok, provider, phone, ccid, msg);
        } finally {
            port.closePort();
        }
    }

    // ==== FALLBACK METHODS ====

    private String tryPhonebook(AtCommandHelper helper) throws IOException, InterruptedException {
        String[] stores = {"SM", "ME", "FD"};
        for (String st : stores) {
            tryCmd(helper, "AT+CPBS=\"" + st + "\"", CMD_TIMEOUT_SHORT_MS);
            for (int i = 1; i <= 10; i++) {
                String resp = tryCmd(helper, "AT+CPBR=" + i, CMD_TIMEOUT_MED_MS);
                String phone = parsePhoneNumberFromCnum(resp);
                if (phone != null) return phone;
            }
        }
        return null;
    }

    private String tryEfMsisdn(AtCommandHelper helper) throws IOException, InterruptedException {
        for (int rec = 1; rec <= 3; rec++) {
            String resp = tryCmd(helper, "AT+CRSM=176,28480," + rec + ",0,20", 2500);
            Matcher m = Pattern.compile("\"([0-9A-F]+)\"").matcher(resp != null ? resp : "");
            if (m.find()) {
                return parseEfMsisdnHex(m.group(1));
            }
        }
        return null;
    }

    private String tryCrossPortSms(AtCommandHelper senderHelper, String senderPort, SerialPort[] allPorts)
            throws IOException, InterruptedException {
        // pick first different port as receiver
        SerialPort receiverPort = Arrays.stream(allPorts)
                .filter(p -> !p.getSystemPortName().equals(senderPort))
                .findFirst().orElse(null);
        if (receiverPort == null) return null;

        receiverPort.setBaudRate(BAUD_RATE);
        receiverPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!receiverPort.openPort()) return null;

        try (AtCommandHelper receiverHelper = new AtCommandHelper(receiverPort)) {
            // assume receiver SIM number known or stored in DB
            String receiverNumber = "+849xxxxxxxx"; // TODO: set đúng số SIM ở receiver
            sendSms(senderHelper, receiverNumber, "Ping from " + senderPort);

            Thread.sleep(5000);
            String inbox = tryCmd(receiverHelper, "AT+CMGL=\"REC UNREAD\"", 5000);
            return extractNumberFromText(inbox);
        } finally {
            receiverPort.closePort();
        }
    }

    // ==== SMS SEND ====

    private void sendSms(AtCommandHelper helper, String number, String message) throws IOException, InterruptedException {
        tryCmd(helper, "AT+CMGF=1", 1000);
        helper.sendCommand("AT+CMGS=\"" + number + "\"", 2000, 0);
        helper.sendCommand(message + "\u001A", 10000, 0);
    }

    // ==== UTILS ====

    private String tryCmd(AtCommandHelper helper, String cmd, int timeout) throws IOException, InterruptedException {
        return helper.sendCommand(cmd, timeout, 0);
    }

    private String parsePhoneNumberFromCnum(String response) {
        if (response == null) return null;
        Matcher m = Pattern.compile("\\+?\\d{7,15}").matcher(response);
        return m.find() ? m.group() : null;
    }

    private String parseCcid(String resp) {
        if (resp == null) return null;
        Matcher m = Pattern.compile("\\d{18,22}").matcher(resp);
        return m.find() ? m.group() : null;
    }

    private String parseImsi(String resp) {
        if (resp == null) return null;
        Matcher m = Pattern.compile("\\d{14,16}").matcher(resp);
        return m.find() ? m.group() : null;
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

    private String parseEfMsisdnHex(String hex) {
        if (hex == null) return null;
        hex = hex.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() % 2 != 0) hex = "0" + hex;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            int b = Integer.parseInt(hex.substring(i, i + 2), 16);
            int low = b & 0x0F, high = (b >> 4) & 0x0F;
            if (low <= 9) sb.append(low); else if (low == 0xF) break;
            if (high <= 9) sb.append(high); else if (high == 0xF) break;
        }
        String s = sb.toString();
        return s.length() >= 7 ? s : null;
    }

    private String extractNumberFromText(String txt) {
        if (txt == null) return null;
        Matcher m = Pattern.compile("\\+?\\d{7,15}").matcher(txt);
        return m.find() ? m.group() : null;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
