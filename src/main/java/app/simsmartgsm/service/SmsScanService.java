package app.simsmartgsm.service;

import app.simsmartgsm.dto.response.SmsResponse;
import app.simsmartgsm.uitils.AtCommandHelper;
import app.simsmartgsm.uitils.AtCommandHelper.SmsRecord;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsScanService {

    private final SimpMessagingTemplate messagingTemplate;
    private final GsmListenerService gsmListenerService;

    // lưu cache tin nhắn cũ để so sánh
    private final Map<String, Set<Integer>> lastSeenIndexByPort = new ConcurrentHashMap<>();

    /**
     * API scan toàn bộ SMS trong 1 COM
     */
    public List<SmsResponse> scanSmsByCom(String comPort) {
        List<SmsResponse> results = new ArrayList<>();
        try (AtCommandHelper helper = AtCommandHelper.open(comPort, 115200, 2000, 2000)) {
            List<SmsRecord> smsList = helper.listAllSmsText(5000);
            for (SmsRecord sms : smsList) {
                results.add(new SmsResponse(
                        comPort,
                        helper.getCnum(),   // số SIM, nếu có
                        sms.sender,
                        formatTimestamp(sms.timestamp),
                        sms.body
                ));
            }
        } catch (Exception e) {
            log.warn("❌ Không đọc được SMS ở {}", comPort, e);
        }
        return results;
    }

    /**
     * Worker chạy nền, 10 giây scan một lần → nếu có tin nhắn mới thì push socket
     */
    @Scheduled(fixedDelay = 10000)
    public void pollNewSms() {
//        SerialPort[] ports = SerialPort.getCommPorts();
//        for (SerialPort port : ports) {
//            String comPort = port.getSystemPortName();
//
//            // ⚡ Bỏ qua nếu port đã có worker (đang dùng cho rent)
//            if (gsmListenerService.hasWorker(comPort)) {
//                log.debug("⏭ Skip scan {} vì đã có worker quản lý", comPort);
//                continue;
//            }
//
//            try (AtCommandHelper helper = AtCommandHelper.open(comPort, 115200, 2000, 2000)) {
//                List<AtCommandHelper.SmsRecord> smsList = helper.listUnreadSmsText(5000);
//                if (smsList.isEmpty()) continue;
//
//                // lấy cache index cũ
//                Set<Integer> lastSeen = lastSeenIndexByPort.computeIfAbsent(comPort, k -> new HashSet<>());
//
//                // lọc ra tin nhắn mới chưa xử lý
//                List<SmsResponse> newMessages = new ArrayList<>();
//                for (AtCommandHelper.SmsRecord sms : smsList) {
//                    if (sms.index != null && !lastSeen.contains(sms.index)) {
//                        String phone = null;
//                        try {
//                            phone = helper.getCnum();
//                        } catch (IOException | InterruptedException ex) {
//                            log.warn("⚠️ Không lấy được số SIM ở {}: {}", comPort, ex.getMessage());
//                        }
//
//                        newMessages.add(new SmsResponse(
//                                comPort,
//                                phone,
//                                sms.sender,
//                                formatTimestamp(sms.timestamp),
//                                sms.body
//                        ));
//
//                        lastSeen.add(sms.index); // update cache
//                    }
//                }
//
//                if (!newMessages.isEmpty()) {
//                    messagingTemplate.convertAndSend("/topic/sms/" + comPort, newMessages);
//                    log.info("📩 Push {} tin nhắn mới từ {}", newMessages.size(), comPort);
//                }
//            } catch (Exception e) {
//                log.warn("❌ Không thể quét SMS ở {}", comPort, e.getMessage());
//            }
//        }
    }


    /**
     * Format timestamp từ modem sang yyyy-MM-dd HH:mm:ss
     */
    private String formatTimestamp(String raw) {
        if (raw == null) return null;
        try {
            // ví dụ raw: 24/09/30,17:45:23+28
            raw = raw.split("\\+")[0]; // bỏ timezone
            java.time.format.DateTimeFormatter inputFmt =
                    java.time.format.DateTimeFormatter.ofPattern("yy/MM/dd,HH:mm:ss");
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(raw, inputFmt);

            java.time.format.DateTimeFormatter outFmt =
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return dt.format(outFmt);
        } catch (Exception e) {
            return raw; // fallback: giữ nguyên nếu parse lỗi
        }
    }
}
