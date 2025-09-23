package app.simsmartgsm.controller;

import app.simsmartgsm.dto.request.SimRequest;
import app.simsmartgsm.service.SimService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sim")
public class SimController {

    private final SimService simService;

    public SimController(SimService simService) {
        this.simService = simService;
    }

    @PostMapping("/send")
    public String sendSim(@RequestParam String vpsId,
                          @RequestBody SimRequest request) {
        return simService.sendSimList(vpsId, request);
    }
}
