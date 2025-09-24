package app.simsmartgsm.controller;

import app.simsmartgsm.service.SmsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sms")
public class SmsController {

    private final SmsService smsService;

    public SmsController(SmsService smsService) {
        this.smsService = smsService;
    }

    // --- 1. Gửi SMS qua 1 port ---
    @PostMapping("/send")
    public String sendSms(@RequestParam String port,
                          @RequestParam String phoneNumber,
                          @RequestParam String message) {
        return smsService.sendSms(port, phoneNumber, message);
    }

    // --- 2. Đọc tất cả SMS cho 1 port ---
    @GetMapping("/read/{port}")
    public String readAllSms(@PathVariable String port) {
        return smsService.toJson(smsService.readAllSmsForPort(port));
    }

    // --- 3. Đọc SMS mới nhất cho 1 port ---
    @GetMapping("/read-latest/{port}")
    public String readLatestSms(@PathVariable String port) {
        return smsService.toJson(
                smsService.readLatestSmsForPort(port) != null
                        ? java.util.Collections.singletonList(smsService.readLatestSmsForPort(port))
                        : java.util.Collections.emptyList()
        );
    }

    // --- 4. Đọc tất cả SMS từ tất cả port (đa luồng) ---
    @GetMapping("/read-all")
    public String readAllSmsAllPorts() {
        return smsService.readAllSmsAllPorts();
    }

    // --- 5. Đọc SMS mới nhất từ tất cả port (đa luồng) ---
    @GetMapping("/read-latest-all")
    public String readLatestSmsAllPorts() {
        return smsService.readLatestSmsAllPorts();
    }
}
