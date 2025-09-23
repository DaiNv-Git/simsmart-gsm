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
                results.add(new PortInfo(portName, false, null, "Cannot open port"));
                continue;
            }

            try {

                sendAtCommand(port, "AT");

                sendAtCommand(port, "AT+CSCS=\"GSM\"");

                String resp = sendAtCommand(port, "AT+CNUM");

                String phone = parsePhoneNumberFromCnum(resp);
                if (phone != null) {
                    results.add(new PortInfo(portName, true, phone, "OK"));
                } else {
                    results.add(new PortInfo(portName, false, null, "No number in CNUM response"));
                }
            } catch (Exception e) {
                results.add(new PortInfo(portName, false, null, "Error: " + e.getMessage()));
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
        // Tìm dòng chứa +CNUM: "Name","<phone>",...
        String[] lines = response.split("\\r?\\n");
        for (String line : lines) {
            if (line.contains("+CNUM")) {
                // tách theo dấu phẩy, lấy phần thứ 2
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    return parts[1].replace("\"", "").trim();
                }
            }
        }
        return null;
    }
}
