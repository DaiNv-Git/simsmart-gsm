package app.simsmartgsm.controller;
import app.simsmartgsm.config.SmsParser;
import app.simsmartgsm.dto.response.SmsMessageUser;
import app.simsmartgsm.service.PortManager;
import app.simsmartgsm.service.SmsSenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/sms")
@RequiredArgsConstructor
@Slf4j
public class SmsController {

    private final SmsSenderService smsSenderService;
    private final PortManager portManager;

    @PostMapping("/send-one")
    public ResponseEntity<String> sendOneSms(
            @RequestParam String comPort,
            @RequestParam String toNumber,
            @RequestParam String message) {

        log.info("📤 API request sendOneSms: {} -> {}: {}", comPort, toNumber, message);

        boolean ok = smsSenderService.sendSms(comPort, toNumber, message);

        if (ok) {
            return ResponseEntity.ok("✅ SMS sent successfully to " + toNumber);
        } else {
            return ResponseEntity.badRequest().body("❌ Failed to send SMS to " + toNumber);
        }
    }

    @GetMapping("/read-all")
    public ResponseEntity<List<SmsMessageUser>> readAllSms(@RequestParam String comPort) {
        log.info("📥 API request readAllSms on {}", comPort);

        List<SmsMessageUser> result = new ArrayList<>();

        try {
            portManager.withPort(comPort, helper -> {
                try {
                    helper.sendAndRead("AT+CMGF=1", 2000);          // text mode
                    helper.sendAndRead("AT+CSCS=\"GSM\"", 2000);    // charset

                    String[] stores = { "SM", "ME", "MT" };
                    for (String store : stores) {
                        helper.sendAndRead("AT+CPMS=\"" + store + "\",\"" + store + "\",\"" + store + "\"", 2000);

                        String resp = helper.sendAndRead("AT+CMGL=\"ALL\"", 10000);
                        log.debug("📥 Raw SMS from {} store {}:\n{}", comPort, store, resp);

                        if (resp != null && resp.contains("+CMGL:")) {
                            result.addAll(SmsParser.parseMulti(resp));
                        }
                    }
                } catch (Exception e) {
                    log.error("❌ Error reading SMS on {}: {}", comPort, e.getMessage(), e);
                }
                return null;
            }, 15000L);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("❌ API readAllSms failed on {}: {}", comPort, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
