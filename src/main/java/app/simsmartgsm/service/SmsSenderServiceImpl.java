package app.simsmartgsm.service.impl;

import app.simsmartgsm.service.SmsSenderService;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class SmsSenderServiceImpl  {
    private static final Logger log = LoggerFactory.getLogger(SmsSenderServiceImpl.class);


    public boolean sendSmsFromPort(SerialPort port, String toNumber, String text)
            throws IOException, InterruptedException {

        boolean openedHere = false;
        if (!port.isOpen()) { openedHere = port.openPort(); }
        if (!port.isOpen()) {
            log.warn("Cannot open port {} to send SMS", port.getSystemPortName());
            return false;
        }

        try (AtCommandHelper helper = new AtCommandHelper(port)) {
            helper.sendCommand("AT", 800, 0);
            helper.sendCommand("AT+CMGF=1", 1000, 0);          // text mode
            helper.sendCommand("AT+CSCS=\"GSM\"", 700, 0);     // charset

            String resp = helper.sendCommand("AT+CMGS=\"" + toNumber + "\"", 3000, 0);
            if (resp != null && resp.contains(">")) {
                // gửi body + Ctrl-Z
                helper.writeRaw(text.getBytes(StandardCharsets.ISO_8859_1));
                helper.writeCtrlZ();
                String sendResp = helper.readResponse(15000);
                log.debug("CMGS response: {}", sendResp);
                return sendResp != null && (sendResp.contains("OK") || sendResp.contains("+CMGS"));
            } else {
                log.warn("No '>' prompt on {}. Resp={}", port.getSystemPortName(), resp);
                return false;
            }
        } finally {
            if (openedHere) try { port.closePort(); } catch (Exception ignore) {}
        }
    }
}
