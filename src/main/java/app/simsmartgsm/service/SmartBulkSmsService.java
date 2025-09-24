package app.simsmartgsm.service;

import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.SmsMessageRepository;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class SmartBulkSmsService {

    private final SmsSenderService smsSenderService;
    private final SmsMessageRepository smsRepo;

    /** Gửi nhiều SMS qua nhiều port chia đều */
    public List<SmsMessage> sendSmartBulk(SmartBulkSmsRequest req, String deviceName) {
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) {
            throw new RuntimeException("❌ Không tìm thấy port nào khả dụng");
        }

        int nPorts = ports.length;
        List<SmsMessage> allResults = Collections.synchronizedList(new ArrayList<>());

        // Thread pool = min(số port, CPU*2)
        ExecutorService exec = Executors.newFixedThreadPool(
                Math.min(nPorts, Runtime.getRuntime().availableProcessors() * 2)
        );

        // phân bổ phones theo round-robin port
        for (int i = 0; i < req.getPhones().size(); i++) {
            String phone = req.getPhones().get(i);
            String portName = ports[i % nPorts].getSystemPortName();

            exec.submit(() -> {
                SmsMessage msg = smsSenderService.sendAndSave(
                        deviceName,
                        portName,
                        phone,
                        req.getText()
                );
                allResults.add(msg);
            });
        }

        exec.shutdown();
        try {
            exec.awaitTermination(2, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Lưu toàn bộ vào DB (dù SmsSenderService đã save từng cái, ta vẫn đảm bảo sync)
        smsRepo.saveAll(allResults);
        return allResults;
    }
}

