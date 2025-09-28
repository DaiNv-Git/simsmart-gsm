package app.simsmartgsm.controller;
import app.simsmartgsm.service.SmsSenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sms")
@RequiredArgsConstructor
@Slf4j
public class SmsController {

    private final SmsSenderService smsSenderService;

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
}
