package app.simsmartgsm.controller;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.service.SimSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller test quét và đồng bộ SIM từ GSM device.
 */
@RestController
@RequestMapping("/api/sim-sync")
@RequiredArgsConstructor
public class SimSyncController {

    private final SimSyncService simSyncService;
    private final SimRepository simRepository;

    /**
     * Trigger quét tất cả cổng COM và đồng bộ DB.
     * @param deviceName tên máy (lấy từ AT+CGMI hoặc config)
     */
    @PostMapping("/scan")
    public String scanAndSync(@RequestParam(defaultValue = "LOCAL-GSM") String deviceName) {
        simSyncService.syncAndResolvePhoneNumbers(deviceName);
        return "✅ Scan & sync started for deviceName=" + deviceName;
    }

    /**
     * Trả về toàn bộ SIM hiện tại trong DB.
     */
    @GetMapping("/list")
    public List<Sim> listAll() {
        return simRepository.findAll();
    }

    /**
     * Lấy SIM theo deviceName.
     */
    @GetMapping("/list/{deviceName}")
    public List<Sim> listByDevice(@PathVariable String deviceName) {
        return simRepository.findByDeviceName(deviceName);
    }
}

