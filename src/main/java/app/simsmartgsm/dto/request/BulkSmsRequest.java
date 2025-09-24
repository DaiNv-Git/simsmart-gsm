package app.simsmartgsm.dto.request;

import java.util.List;

public class BulkSmsRequest {

        private List<String> phoneNumbers;
        private String message;

        public List<String> getPhoneNumbers() {
            return phoneNumbers;
        }
        public void setPhoneNumbers(List<String> phoneNumbers) {
            this.phoneNumbers = phoneNumbers;
        }
        public String getMessage() {
            return message;
        }
        public void setMessage(String message) {
            this.message = message;
        }
}
