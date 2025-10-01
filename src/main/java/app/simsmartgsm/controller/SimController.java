package app.simsmartgsm.controller;

import app.simsmartgsm.dto.response.SimResponse;
import app.simsmartgsm.service.SimScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sim")
@RequiredArgsConstructor
public class SimController {

    private final SimScanService simScanService;

    @GetMapping
    public List<SimResponse> getAllSims() {
        return simScanService.scanAllSims();
    }

    @GetMapping("/{comPort}")
    public SimResponse getSimByCom(@PathVariable String comPort) {
        return simScanService.scanSimByCom(comPort);
    }
}
