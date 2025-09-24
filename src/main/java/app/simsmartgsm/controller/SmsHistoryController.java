package app.simsmartgsm.controller;
import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.service.SmsHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sms/history")
@RequiredArgsConstructor
public class SmsHistoryController {

    private final SmsHistoryService smsHistoryService;

    @GetMapping("/inbox")
    public Page<Map<String, String>> getInbox(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return smsHistoryService.getInboxMessages(page, size);
    }

    @GetMapping("/sent")
    public Page<SmsMessage> getSent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return smsHistoryService.getSentMessages(page, size);
    }

    @GetMapping("/failed")
    public Page<SmsMessage> getFailed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return smsHistoryService.getFailedMessages(page, size);
    }
}
