package app.simsmartgsm.service;

import app.simsmartgsm.dto.response.SimResponse;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimScanService {

    private final SimpMessagingTemplate messagingTemplate;

    /** Quét toàn bộ COM port và đẩy kết quả lên socket */
    public List<SimResponse> scanAllSims() {
        List<SimResponse> results = new ArrayList<>();

        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            String comPort = port.getSystemPortName();
            try (AtCommandHelper helper = AtCommandHelper.open(comPort, 115200, 2000, 2000)) {
                String iccid = helper.queryIccid();
                String phoneNumber = helper.queryMsisdn();
                String provider = helper.queryOperator();

                results.add(new SimResponse(
                        comPort,
                        "ONLINE",
                        provider,
                        phoneNumber,
                        iccid,
                        "OK"
                ));
            } catch (Exception e) {
                log.warn("❌ Không đọc được SIM ở {}", comPort, e);
                results.add(new SimResponse(
                        comPort,
                        "OFFLINE",
                        null,
                        null,
                        null,
                        "Không đọc được SIM"
                ));
            }
        }

        // ✅ Push lên WebSocket
        messagingTemplate.convertAndSend("/topic/sims", results);

        return results;
    }

    /** Quét 1 COM port cụ thể và push lên socket */
    public SimResponse scanSimByCom(String comPort) {
        SimResponse response;
        try (AtCommandHelper helper = AtCommandHelper.open(comPort, 115200, 2000, 2000)) { // ✅ gọi đủ tham số
            String iccid = helper.queryIccid();
            String phoneNumber = helper.queryMsisdn();
            String provider = helper.queryOperator();

            response = new SimResponse(
                    comPort,
                    "ONLINE",
                    provider,
                    phoneNumber,
                    iccid,
                    "OK"
            );
        } catch (Exception e) {
            log.warn("❌ Không đọc được SIM ở {}", comPort, e);
            response = new SimResponse(
                    comPort,
                    "OFFLINE",
                    null,
                    null,
                    null,
                    "Không đọc được SIM"
            );
        }

        messagingTemplate.convertAndSend("/topic/sims/" + comPort, response);

        return response;
    }
}
