package app.simsmartgsm.service;

import com.fazecast.jSerialComm.SerialPort;
import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class PortScannerService {

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

            try {
                // Kiểm tra kết nối modem
                sendAtCommand(port, "AT");
                sendAtCommand(port, "AT+CSCS=\"GSM\"");

                // Lấy số điện thoại
                String respNum = sendAtCommand(port, "AT+CNUM");
                String phone = parsePhoneNumberFromCnum(respNum);

                // Lấy CCID
                String respCcid = sendAtCommand(port, "AT+CCID");
                String ccid = parseCcid(respCcid);

                // Lấy IMSI để xác định nhà mạng
                String respImsi = sendAtCommand(port, "AT+CIMI");
                String imsi = parseImsi(respImsi);
                String provider = detectProvider(imsi);

                if (phone != null || ccid != null) {
                    results.add(new PortInfo(portName, true, provider, phone, ccid, "OK"));
                } else {
                    results.add(new PortInfo(portName, false, null, null, null, "No data from SIM"));
                }

            } catch (Exception e) {
                results.add(new PortInfo(portName, false, null, null, null, "Error: " + e.getMessage()));
            } finally {
                port.closePort();
            }
        }

        return results;
    }

    private String sendAtCommand(SerialPort port, String command) throws IOException, InterruptedException {
        String atCmd = command + "\r";
        port.getOutputStream().write(atCmd.getBytes());
        port.getOutputStream().flush();

        Thread.sleep(300);

        byte[] buffer = new byte[4096];
        int read = port.getInputStream().read(buffer);
        if (read > 0) {
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
        return "";
    }

    private String parsePhoneNumberFromCnum(String response) {
        if (response == null) return null;
        for (String line : response.split("\\r?\\n")) {
            if (line.contains("+CNUM")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    return parts[1].replace("\"", "").trim();
                }
            }
        }
        return null;
    }

    private String parseCcid(String response) {
        if (response == null) return null;
        for (String line : response.split("\\r?\\n")) {
            if (line.matches("\\d{18,20}") || line.contains("+CCID")) {
                return line.replace("+CCID: ", "").trim();
            }
        }
        return null;
    }

    private String parseImsi(String response) {
        if (response == null) return null;
        for (String line : response.split("\\r?\\n")) {
            if (line.matches("\\d{14,16}")) {
                return line.trim();
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
}
