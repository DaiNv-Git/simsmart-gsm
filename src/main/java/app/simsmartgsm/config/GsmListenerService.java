package app.simsmartgsm.config;

import app.simsmartgsm.uitils.PortWorker;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class GsmListenerService {
    private final Map<String, PortWorker> workers = new ConcurrentHashMap<>();

    @PreDestroy
    public void cleanup() {
        log.info("⏹️ Application stopping, closing all COM ports...");
        workers.values().forEach(PortWorker::stop);
        workers.clear();
    }
}
