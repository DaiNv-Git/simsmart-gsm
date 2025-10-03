package app.simsmartgsm.service;
import app.simsmartgsm.entity.CallRecord;
import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.CallRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CallRecordService {

    private final CallRecordRepository callRecordRepository;

    public CallRecord saveCallRecord(
            String orderId,
            Long customerId,
            Sim sim,
            String fromNumber,
            String status,
            String recordFile,
            Instant callStartTime,
            Instant callEndTime,
            Instant expireAt
    ) {
        CallRecord record = CallRecord.builder()
                .orderId(orderId)
                .customerId(customerId)
                .simPhone(sim.getPhoneNumber())
                .fromNumber(fromNumber)
                .deviceName(sim.getDeviceName())
                .comPort(sim.getComName())
                .countryCode(sim.getCountryCode())
                .serviceCode("CALL")
                .status(status)
                .recordFile(recordFile)
                .callStartTime(callStartTime)
                .callEndTime(callEndTime)
                .expireAt(expireAt)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return callRecordRepository.save(record);
    }
}
