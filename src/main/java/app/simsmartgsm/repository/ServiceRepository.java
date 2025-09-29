package app.simsmartgsm.repository;

import app.simsmartgsm.entity.Service;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ServiceRepository extends MongoRepository<Service, String> {
    Optional<Service> findByCode(String code);
}
