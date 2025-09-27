package app.simsmartgsm.controller;

import app.simsmartgsm.dto.response.RentSimRequest;
import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.CountryRepository;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.service.GsmListenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@Slf4j
public class OtpWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final GsmListenerService gsmListenerService;
    private final SimRepository simRepository;
    private final CountryRepository countryRepository;

    @MessageMapping("/send-otp")
    public void handleSendOtp(@Payload RentSimRequest request) {
        log.info("📩 Nhận từ client /app/send-otp: {}", request);

        Sim sim = simRepository.findById(request.getSimId())
                .orElseThrow(() -> new RuntimeException("SIM not found: " + request.getSimId()));

        Country country = countryRepository.findByCountryCode(request.getCountryCode())
                .orElseThrow(() -> new RuntimeException("Country not found: " + request.getCountryCode()));

        gsmListenerService.rentSim(
                sim,
                request.getAccountId(),
                request.getServiceCode(),
                request.getRentDuration(),
                country
        );

        Map<String, Object> response = new HashMap<>();
        response.put("status", "SESSION_CREATED");
        response.put("simId", sim.getId());
        response.put("accountId", request.getAccountId());
        response.put("serviceCode", request.getServiceCode());
        response.put("duration", request.getRentDuration());
        response.put("country", country.getCountryCode());

        messagingTemplate.convertAndSend("/topic/receive-otp", response);
        log.info("📤 Gửi xác nhận thuê SIM về /topic/receive-otp: {}", response);
    }

}
