package app.simsmartgsm.service;

import com.fazecast.jSerialComm.SerialPort;
import app.simsmartgsm.dto.request.SimRequest;
import app.simsmartgsm.dto.request.SimRequest.PortSimple;
import app.simsmartgsm.util.AtCommandHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class PortScannerService {

    /**
     * Quét tất cả COM ports, lấy phone number bằng AT+CNUM (nếu modem trả).
     * Trả về list chỉ chứa portName và phoneNumber (có thể null).
     */
    public List<PortSimple> scanAllPorts() {
        List<PortSimple> results = new ArrayList<>();
        SerialPort[] ports = SerialPort.getCommPorts();

        for (SerialPort port : ports) {
            String portName = port.getSystemPortName();
            // set common params
            port.setBaudRate(115200);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

            if (!port.openPort()) {
                results.add(new PortSimple(portName, null));
                continue;
            }

            AtCommandHelper helper = new AtCommandHelper(port);
            try {
                // ensure modem responds
                String atResp = helper.sendCommand("AT", 800);
                if (atResp.isEmpty()) {
                    // no response -> mark null
                    results.add(new PortSimple(portName, null));
                    continue;
                }

                // optional: set charset to GSM to help some modems
                helper.sendCommand("AT+CSCS=\"GSM\"", 500);

                // ask CNUM (may be empty on many SIMs)
                String cnumResp = helper.sendCommand("AT+CNUM", 2000);

                String phone = parsePhoneFromCnum(cnumResp);

                results.add(new PortSimple(portName, phone));
            } catch (IOException | InterruptedException e) {
                results.add(new PortSimple(portName, null));
            } finally {
                port.closePort();
            }
        }

        return results;
    }

    private String parsePhoneFromCnum(String resp) {
        if (resp == null || resp.isEmpty()) return null;
        // example: +CNUM: ""," +84901234567",145,7,0
        String[] lines = resp.split("\\r?\\n");
        for (String line : lines) {
            if (line.contains("+CNUM")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    String num = parts[1].replace("\"", "").trim();
                    if (!num.isEmpty()) return num;
                }
            }
            String t = line.trim();
            if (t.matches("\\+?\\d{7,15}")) return t;
        }
        return null;
    }
}
