package app.simsmartgsm.repository;
// SimRepository.java

import app.simsmartgsm.entity.Sim;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SimRepository extends MongoRepository<Sim, String> {
    Optional<Sim> findFirstByCcid(String ccid);
    Optional<Sim> findByPhoneNumber(String phoneNumber);
    List<Sim> findByDeviceName(String deviceName);
    List<Sim> findByStatus(String status);
    Optional<Sim> findByDeviceNameAndCcid(String deviceName,String ccid);
    Optional<Sim> findFirstByImsi(String imsi);
    Optional<Sim> findFirstByPhoneNumber(String phoneNumber);
}
