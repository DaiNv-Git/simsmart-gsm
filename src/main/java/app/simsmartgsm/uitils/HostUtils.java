package app.simsmartgsm.uitils;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class HostUtils {

    public static String getDeviceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) {
                return System.getenv().getOrDefault("COMPUTERNAME", "unknown-device");
            } else {
                return System.getenv().getOrDefault("HOSTNAME", "unknown-device");
            }
        }
    }
}
