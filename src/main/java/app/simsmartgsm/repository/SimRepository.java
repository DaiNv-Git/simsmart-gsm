package app.simsmartgsm.repository;
// SimRepository.java

import app.simsmartgsm.entity.Sim;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SimRepository extends MongoRepository<Sim, String> {
    Optional<Sim> findFirstByCcid(String ccid);
    Optional<Sim> findFirstByImsi(String imsi);
}
