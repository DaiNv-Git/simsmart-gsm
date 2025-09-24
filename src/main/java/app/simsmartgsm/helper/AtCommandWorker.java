package app.simsmartgsm.helper;

import com.fazecast.jSerialComm.SerialPort;
import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import app.simsmartgsm.uitils.AtCommandHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AtCommandWorker {

    private static final Logger log = LoggerFactory.getLogger(AtCommandWorker.class);

    private final String portName;

    public AtCommandWorker(String portName) {
        this.portName = portName;
    }

    public PortInfo doScan() {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

        try {
            if (!port.openPort()) {
                return new PortInfo(portName, false, null, null, null, "Cannot open port");
            }

            AtCommandHelper helper = new AtCommandHelper(port);

            String ccid = parseCcid(helper.sendCommand("AT+CCID", 1000, 0));
            String imsi = parseImsi(helper.sendCommand("AT+CIMI", 1000, 0));
            String phone = parsePhoneNumberFromCnum(helper.sendCommand("AT+CNUM", 1000, 0));
            String provider = detectProvider(imsi);

            boolean ok = (ccid != null) || (imsi != null) || (phone != null);
            String msg = ok ? "OK" : "No data";

            return new PortInfo(portName, ok, provider, phone, ccid, msg);
        } catch (Exception e) {
            return new PortInfo(portName, false, null, null, null, "Error: " + e.getMessage());
        } finally {
            if (port.isOpen()) {
                port.closePort();
                log.info("Closed port {}", portName);
            }
        }
    }

    // --- parsers ---
    private String parsePhoneNumberFromCnum(String response) {
        if (response == null) return null;
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
        if (response == null) return null;
        for (String line : response.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("+CCID")) return l.replace("+CCID:", "").replace(" ", "").trim();
            if (l.matches("\\d{18,22}")) return l;
        }
        return null;
    }

    private String parseImsi(String response) {
        if (response == null) return null;
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
}
