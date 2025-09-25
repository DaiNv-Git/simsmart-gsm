package app.simsmartgsm.service;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class SmsSenderServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(SmsSenderServiceImpl.class);

    public boolean sendSmsFromPort(SerialPort port, String toNumber, String text) throws IOException, InterruptedException {
        if (!port.isOpen()) {
            if (!port.openPort()) {
                log.warn("Cannot open port to send SMS: {}", port.getSystemPortName());
                return false;
            }
        }
        AtCommandHelper helper = new AtCommandHelper(port);

        // set text mode
        helper.sendCommand("AT+CMGF=1", 1000, 0);

        // optional: set character set
        helper.sendCommand("AT+CSCS=\"GSM\"", 700, 0);

        // Use AT+CMGS: <recipient> then wait for '>' prompt then send text + Ctrl+Z
        // We assume AtCommandHelper has a method to write raw bytes; adjust to your helper.
        String resp = helper.sendCommand("AT+CMGS=\"" + toNumber + "\"", 2000, 0);
        // many modems will reply with '>' prompt — helper should enable writing following text and Ctrl-Z
        if (resp != null && resp.contains(">")) {
            // send text + ctrl-z
            helper.writeRaw((text + "\u001A").getBytes()); // helper must support writeRaw
            String sendResp = helper.readResponse(15000); // wait up to 15s for final response
            log.debug("CMGS result: {}", sendResp);
            return sendResp != null && (sendResp.contains("OK") || sendResp.contains("+CMGS"));
        } else {
            log.warn("No prompt '>' for CMGS on port {}. Resp: {}", port.getSystemPortName(), resp);
            // fallback: try a single-line send if helper implements it
            return false;
        }
    }
}
