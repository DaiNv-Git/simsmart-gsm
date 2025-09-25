package app.simsmartgsm.controller;

import app.simsmartgsm.dto.response.SimResponse;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.service.SimSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sim-sync")
@RequiredArgsConstructor
public class SimSyncController {

    private final SimSyncService simSyncService;
    private final SimRepository simRepository;

    /** Gọi API này để scan toàn bộ COM và resolve số cho SIM chưa biết */
    @PostMapping("/scan")
    public String scanAndResolve() throws Exception {
        simSyncService.syncAndResolve();
        return "✅ Scan & resolve";
    }

    @GetMapping("/sims")
    public List<SimResponse> getSimsByDeviceName() throws UnknownHostException {
        String deviceName = InetAddress.getLocalHost().getHostName();
        return mapToResponse(simRepository.findByDeviceName(deviceName));
    }

    private List<SimResponse> mapToResponse(List<Sim> sims) {
        return sims.stream()
                .map(s -> new SimResponse(
                        s.getComName(),
                        s.getStatus(),
                        s.getSimProvider(),
                        s.getPhoneNumber(),
                        s.getCcid(),
                        s.getContent()
                ))
                .collect(Collectors.toList());
    }
}
