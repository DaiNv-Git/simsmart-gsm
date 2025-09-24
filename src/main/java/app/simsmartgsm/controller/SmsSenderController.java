package app.simsmartgsm.controller;

import app.simsmartgsm.dto.request.BulkSmsRequest;
import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.service.SmsSenderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/sms")
@RequiredArgsConstructor
public class SmsSenderController {

    private final SmsSenderService smsSenderService;

    @PostMapping("/send-bulk")
    public List<SmsMessage> sendBulk(@RequestBody BulkSmsRequest request) {
        return smsSenderService.sendBulk(request.getPhoneNumbers(), request.getMessage());
    }


}
