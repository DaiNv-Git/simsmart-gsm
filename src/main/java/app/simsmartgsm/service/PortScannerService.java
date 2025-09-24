package app.simsmartgsm.service;

import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PortScannerService {

    private static final Logger log = LoggerFactory.getLogger(PortScannerService.class);

    private static final int BAUD_RATE = 115200;

    public List<PortInfo> scanAllPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        List<PortInfo> results = new ArrayList<>();

        for (SerialPort port : ports) {
            String portName = port.getSystemPortName();
            log.info("Đang quét cổng {}", portName);

            PortInfo info;
            try {
                info = scanSinglePort(port);
            } catch (Exception e) {
                log.error("Lỗi khi quét {}: {}", portName, e.getMessage());
                info = new PortInfo(portName, false, null, null, null, "Error: " + e.getMessage());
            }

            results.add(info);
        }
        return results;
    }

    private PortInfo scanSinglePort(SerialPort port) throws IOException, InterruptedException {
        String portName = port.getSystemPortName();

        port.setBaudRate(BAUD_RATE);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 3000, 3000);

        if (!port.openPort()) {
            return new PortInfo(portName, false, null, null, null, "Cannot open port");
        }

        try (AtCommandHelper helper = new AtCommandHelper(port)) {
            // Check modem
            String atResp = helper.sendCommand("AT", 1000, 0);
            if (atResp == null || !atResp.contains("OK")) {
                return new PortInfo(portName, false, null, null, null, "No AT response");
            }

            // Lấy ICCID
            String ccidResp = helper.sendCommand("AT+CCID", 2000, 0);
            String iccid = extractDigits(ccidResp);

            // Lấy IMSI
            String imsiResp = helper.sendCommand("AT+CIMI", 2000, 0);
            String imsi = extractDigits(imsiResp);

            // Thử nhiều cách để lấy số điện thoại
            String msisdn = tryGetMsisdn(helper);

            if (msisdn == null) {
                msisdn = "<UNKNOWN>"; // fallback cuối cùng
            }

            boolean ok = iccid != null || imsi != null;
            String msg = ok ? "OK" : "No data";

            return new PortInfo(portName, ok, detectProvider(imsi), msisdn, iccid, msg);

        } finally {
            port.closePort();
        }
    }

    private String tryGetMsisdn(AtCommandHelper helper) throws IOException, InterruptedException {
        // 1. Thử AT+CNUM
        String cnumResp = helper.sendCommand("AT+CNUM", 3000, 0);
        String msisdn = extractPhone(cnumResp);
        if (msisdn != null) return msisdn;

        // 2. Thử phonebook (CPBS + CPBR)
        String cpbs = helper.sendCommand("AT+CPBS=\"SM\"", 2000, 0);
        log.debug("CPBS resp: {}", cpbs);
        String cpbrResp = helper.sendCommand("AT+CPBR=1,5", 4000, 0);
        msisdn = extractPhone(cpbrResp);
        if (msisdn != null) return msisdn;

        // 3. Thử đọc EF_MSISDN qua CRSM
        for (int rec = 1; rec <= 3; rec++) {
            String crsm = helper.sendCommand("AT+CRSM=176,28480," + rec + ",0,20", 5000, 0);
            msisdn = parseEfMsisdnHex(crsm);
            if (msisdn != null) return msisdn;
        }

        // Nếu tất cả fail → null
        return null;
    }

    private String extractDigits(String input) {
        if (input == null) return null;
        return input.replaceAll("[^0-9]", "");
    }

    private String extractPhone(String input) {
        if (input == null) return null;
        Matcher m = Pattern.compile("\\+?\\d{7,15}").matcher(input);
        return m.find() ? m.group() : null;
    }

    private String parseEfMsisdnHex(String resp) {
        if (resp == null) return null;
        Matcher m = Pattern.compile("\"([0-9A-F]+)\"").matcher(resp);
        if (!m.find()) return null;

        String hex = m.group(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            int b = Integer.parseInt(hex.substring(i, i + 2), 16);
            int low = b & 0x0F, high = (b >> 4) & 0x0F;
            if (low <= 9) sb.append(low);
            if (high <= 9) sb.append(high);
        }
        String number = sb.toString();
        return number.length() >= 7 ? number : null;
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
}
