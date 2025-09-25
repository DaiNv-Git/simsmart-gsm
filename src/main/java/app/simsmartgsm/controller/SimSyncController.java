package app.simsmartgsm.controller;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.SimRepository;
import app.simsmartgsm.service.SimSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.List;

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

    /** Xem toàn bộ SIM trong DB */
    @GetMapping("/list")
    public List<Sim> listAll() {
        return simRepository.findAll();
    }
}
