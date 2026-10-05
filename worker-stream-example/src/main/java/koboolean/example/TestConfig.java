package koboolean.example;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;

@Component
public class TestConfig {

    @Bean
    CommandLineRunner testVllmConnection() {
        return args -> {
            try (var socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress("192.168.45.121", 8000),
                        3000
                );

                System.out.println(
                        ">>> JAVA SOCKET CONNECTED = "
                                + socket.getRemoteSocketAddress()
                );
            } catch (Exception e) {
                System.err.println(">>> JAVA SOCKET FAILED");
                e.printStackTrace();
            }
        };
    }
}
