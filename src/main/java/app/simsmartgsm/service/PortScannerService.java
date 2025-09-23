package app.simsmartgsm.service;

import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class PortScannerService {

    /**
     * Quét tất cả cổng COM, dùng AtCommandHelper để đọc AT responses ổn định.
     */
    public List<PortInfo> scanAllPorts() {
        List<PortInfo> results = new ArrayList<>();
        SerialPort[] ports = SerialPort.getCommPorts();

        for (SerialPort port : ports) {
            String portName = port.getSystemPortName();
            port.setBaudRate(115200);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

            if (!port.openPort()) {
                results.add(new PortInfo(portName, false, null, null, null, "Cannot open port"));
                continue;
            }

            AtCommandHelper helper = new AtCommandHelper(port);
            try {
                // chạy test AT để đảm bảo modem phản hồi
                String respAt = helper.sendCommand("AT", 1000, 2);
                if (respAt.isEmpty()) {
                    results.add(new PortInfo(portName, false, null, null, null, "No response to AT"));
                    continue;
                }

                // set charset để dễ parse (GSM / UTF-8 fallback)
                helper.sendCommand("AT+CSCS=\"GSM\"", 800, 1);

                // Lấy CCID (ICCID)
                String respCcid = helper.sendCommand("AT+CCID", 2000, 2);
                String ccid = parseCcid(respCcid);

                // Lấy IMSI (CIMI)
                String respImsi = helper.sendCommand("AT+CIMI", 2000, 2);
                String imsi = parseImsi(respImsi);
                String provider = detectProvider(imsi);

                // Lấy phone number (CNUM) - có modem trả rỗng
                String respCnum = helper.sendCommand("AT+CNUM", 2500, 2);
                String phone = parsePhoneNumberFromCnum(respCnum);

                // Nếu không có phone, một số môi trường dùng AT+CGSN hoặc AT+CPBR => nhưng ta tạm fallback
                if (phone == null && ccid != null) {
                    // nếu muốn có chiến lược khác, có thể implement: gửi SMS test, hoặc đọc từ DB mapping ICCID->phone
                }

                boolean ok = phone != null || ccid != null || imsi != null;
                String message = ok ? "OK" : "No data from SIM";

                results.add(new PortInfo(portName, ok, provider, phone, ccid, message));
            } catch (IOException | InterruptedException e) {
                results.add(new PortInfo(portName, false, null, null, null, "Error: " + e.getMessage()));
            } finally {
                port.closePort();
            }
        }

        return results;
    }

    // ---- Parsers ----
    private String parsePhoneNumberFromCnum(String response) {
        if (response == null || response.isEmpty()) return null;
        String[] lines = response.split("\\r?\\n");
        for (String line : lines) {
            if (line.contains("+CNUM")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    return parts[1].replace("\"", "").trim();
                }
            }
            // some modems may return number without +CNUM label - try pattern digits
            String t = line.trim();
            if (t.matches("\\+?\\d{7,15}")) return t;
        }
        return null;
    }

    private String parseCcid(String response) {
        if (response == null || response.isEmpty()) return null;
        String[] lines = response.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("+CCID")) {
                return line.replace("+CCID:", "").replace(" ", "").trim();
            }
            if (line.matches("\\d{18,22}")) {
                return line.trim();
            }
        }
        return null;
    }

    private String parseImsi(String response) {
        if (response == null || response.isEmpty()) return null;
        String[] lines = response.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.matches("\\d{14,16}")) return line;
        }
        return null;
    }

    private String detectProvider(String imsi) {
        if (imsi == null) return null;
        // một số prefix ví dụ (bạn mở rộng theo vùng/quốc gia)
        if (imsi.startsWith("44010")) return "NTT Docomo (JP)";
        if (imsi.startsWith("44020")) return "SoftBank (JP)";
        if (imsi.startsWith("44050")) return "KDDI au (JP)";
        if (imsi.startsWith("45201")) return "Mobifone (VN)";
        if (imsi.startsWith("45202")) return "Vinaphone (VN)";
        if (imsi.startsWith("45204")) return "Viettel (VN)";
        return "Unknown";
    }
}
