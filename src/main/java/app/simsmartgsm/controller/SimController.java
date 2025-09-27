package app.simsmartgsm.controller;

import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.CountryRepository;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.service.GsmListenerService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sim")
@RequiredArgsConstructor
public class SimController {

    private final GsmListenerService gsmListenerService;
    private final SimRepository simRepository;
    private final CountryRepository countryRepository;

    @PostConstruct
    public void init() {
        // Khi app start -> bật listener cho tất cả SIM đang active
        simRepository.findAll().forEach(sim -> gsmListenerService.startListener(sim));
    }

    @PostMapping("/rent")
    public ResponseEntity<String> rentSim(
            @RequestParam String simId,
            @RequestParam Long accountId,
            @RequestParam List<String> services,
            @RequestParam int duration,
            @RequestParam String countryCode) {

        Sim sim = simRepository.findById(simId)
                .orElseThrow(() -> new RuntimeException("SIM not found"));
        Country country = countryRepository.findByCountryCode(countryCode)
                .orElseThrow(() -> new RuntimeException("Country not found"));

        gsmListenerService.rentSim(sim, accountId, services, duration, country);

        return ResponseEntity.ok("Sim rented successfully");
    }
}
