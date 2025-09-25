package app.simsmartgsm.service;


import app.simsmartgsm.entity.Sim;
import org.springframework.stereotype.Service;
import app.simsmartgsm.repository.SimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@Service
public class IncomingSmsProcessor {
    private static final Logger log = LoggerFactory.getLogger(IncomingSmsProcessor.class);

    private final SimRepository simRepository;

    public IncomingSmsProcessor(SimRepository simRepository) {
        this.simRepository = simRepository;
    }

    /**
     * Called by your external SMS gateway when it receives an inbound SMS.
     * - fromNumber is the originating MSISDN (the SIM we need to bind)
     * - body can optionally carry a token if you used token in message body.
     *
     * This method will attempt to find a Sim with matching CCID or with some pending marker.
     * For simplicity we just search for sims with null phoneNumber and ccid present and set phoneNumber to fromNumber.
     */
    public void processInboundSms(String fromNumber, String body) {
        log.info("Inbound SMS from {} body={}", fromNumber, body);

        // Try to find SIM by some logic: for example, find first Sim without phoneNumber
        // In production you'll likely have a pending token map: token -> ccid
        Optional<Sim> candidate = simRepository.findAll().stream()
                .filter(s -> (s.getPhoneNumber() == null || s.getPhoneNumber().isEmpty()))
                .findFirst();

        if (candidate.isPresent()) {
            Sim s = candidate.get();
            s.setPhoneNumber(fromNumber);
            s.setStatus("active");
            s.setLastUpdated(new java.util.Date().toInstant());
            simRepository.save(s);
            log.info("Assigned phone {} to sim id={} ccid={}", fromNumber, s.getId(), s.getCcid());
        } else {
            log.warn("No candidate SIM found to assign incoming number {}", fromNumber);
        }
    }
}
