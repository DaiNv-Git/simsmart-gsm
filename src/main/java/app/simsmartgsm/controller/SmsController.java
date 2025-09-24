package app.simsmartgsm.controller;
import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.service.SmsSenderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsSenderService smsSenderService;

    @PostMapping("/send/bulk")
    public List<SmsMessage> sendBulkSms(
            @RequestParam String text,
            @RequestBody List<String> phoneNumbers
    ) {
        return smsSenderService.sendBulkSms(phoneNumbers, text);
    }

    /**
     * Lấy tất cả lịch sử tin nhắn đã lưu
     */
    @GetMapping("/history")
    public List<SmsMessage> getHistory() {
        return smsSenderService.getAllMessages();
    }
}

