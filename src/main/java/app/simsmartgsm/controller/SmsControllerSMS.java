package app.simsmartgsm.controller;

import app.simsmartgsm.service.PortManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/sms/test")
@Slf4j
@RequiredArgsConstructor
public class SmsControllerSMS {

    private final PortManager portManager;

    /**
     * Gửi SMS text
     */
    @PostMapping("/send")
    public ResponseEntity<String> sendSms(
            @RequestParam String comPort,
            @RequestParam String toNumber,
            @RequestParam String message) {
        try {
            Boolean ok = portManager.withPort(comPort, helper -> {
                try {
                    return helper.sendTextSms(toNumber, message, Duration.ofSeconds(30));
                } catch (Exception e) {
                    log.error("❌ Error sending SMS via {}: {}", comPort, e.getMessage());
                    return false;
                }
            }, 15000);

            if (Boolean.TRUE.equals(ok)) {
                return ResponseEntity.ok("✅ SMS sent successfully to " + toNumber);
            } else {
                return ResponseEntity.internalServerError().body("❌ Failed to send SMS");
            }
        } catch (Exception e) {
            log.error("❌ Error sending SMS via {}: {}", comPort, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("❌ " + e.getMessage());
        }
    }

    /**
     * Đọc tất cả SMS
     */
    @GetMapping("/read-all")
    public ResponseEntity<?> readAll(@RequestParam String comPort) {
        try {
            var list = portManager.withPort(comPort, helper -> {
                try {
                    return helper.listAllSmsText(5000);
                } catch (Exception e) {
                    log.error("❌ Error reading ALL SMS on {}: {}", comPort, e.getMessage());
                    return null;
                }
            }, 10000);

            return ResponseEntity.ok(list);
        } catch (Exception e) {
            log.error("❌ Error reading SMS via {}: {}", comPort, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("❌ " + e.getMessage());
        }
    }

    /**
     * Đọc SMS chưa đọc
     */
    @GetMapping("/read-unread")
    public ResponseEntity<?> readUnread(@RequestParam String comPort) {
        try {
            var list = portManager.withPort(comPort, helper -> {
                try {
                    return helper.listUnreadSmsText(5000);
                } catch (Exception e) {
                    log.error("❌ Error reading UNREAD SMS on {}: {}", comPort, e.getMessage());
                    return null;
                }
            }, 10000);

            return ResponseEntity.ok(list);
        } catch (Exception e) {
            log.error("❌ Error reading unread SMS via {}: {}", comPort, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("❌ " + e.getMessage());
        }
    }

    /**
     * Xoá toàn bộ SMS
     */
    @DeleteMapping("/delete-all")
    public ResponseEntity<String> deleteAll(@RequestParam String comPort) {
        try {
            Boolean ok = portManager.withPort(comPort, helper -> {
                try {
                    return helper.deleteAllSms();
                } catch (Exception e) {
                    log.error("❌ Error deleting SMS on {}: {}", comPort, e.getMessage());
                    return false;
                }
            }, 10000);

            if (Boolean.TRUE.equals(ok)) {
                return ResponseEntity.ok("🗑️ Deleted all SMS from " + comPort);
            } else {
                return ResponseEntity.internalServerError().body("❌ Failed to delete SMS");
            }
        } catch (Exception e) {
            log.error("❌ Error deleting SMS via {}: {}", comPort, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("❌ " + e.getMessage());
        }
    }
}
