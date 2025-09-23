package app.simsmartgsm.service;

import app.simsmartgsm.dto.request.SimRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class SimService {

    private final RestTemplate restTemplate;

    public SimService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String sendSimList(String vpsId, SimRequest payload) {
        String url = String.format("http://%s:9090/api/simlist", vpsId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", System.getenv("API_KEY"));

        HttpEntity<SimRequest> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return "Gửi dữ liệu thành công: " + response.getBody();
            } else {
                return "Lỗi khi gửi dữ liệu, status=" + response.getStatusCode()
                        + " body=" + response.getBody();
            }

        } catch (RestClientException e) {
            return "❌ Lỗi khi gửi request: " + e.getMessage();
        }
    }
}
