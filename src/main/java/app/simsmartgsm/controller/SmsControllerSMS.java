package app.simsmartgsm.controller;
import app.simsmartgsm.service.SimpleGsmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sms/test")
@Slf4j
public class SmsControllerSMS {
    @PostMapping("/send")
    public ResponseEntity<String> sendSms(
            @RequestParam String comPort,
            @RequestParam String toNumber,
            @RequestParam String message) {
        try {
            // Lấy client với port do PortManager quản lý
            SimpleGsmClient client = new SimpleGsmClient(comPort);

            // Gửi SMS (bên trong đã có synchronized theo port)
            client.sendSms(toNumber, message);

            return ResponseEntity.ok("✅ SMS sent successfully to " + toNumber);
        } catch (Exception e) {
            log.error("❌ Error sending SMS via {}: {}", comPort, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("❌ " + e.getMessage());
        }
    }


    /**
     * Đọc tất cả SMS
     */
    @GetMapping("/read-all")
    public ResponseEntity<String> readAll(@RequestParam String comPort) {
        try (SimpleGsmClient client = new SimpleGsmClient(comPort)) {
            String resp = client.readAllSms();
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("❌ Error reading SMS via {}: {}", comPort, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("❌ " + e.getMessage());
        }
    }

    /**
     * Đọc SMS chưa đọc
     */
    @GetMapping("/read-unread")
    public ResponseEntity<String> readUnread(@RequestParam String comPort) {
        try (SimpleGsmClient client = new SimpleGsmClient(comPort)) {
            String resp = client.readUnreadSms();
            return ResponseEntity.ok(resp);
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
        try (SimpleGsmClient client = new SimpleGsmClient(comPort)) {
            client.deleteAllSms();
            return ResponseEntity.ok("🗑️ Deleted all SMS from " + comPort);
        } catch (Exception e) {
            log.error("❌ Error deleting SMS via {}: {}", comPort, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("❌ " + e.getMessage());
        }
    }
    
    
}
