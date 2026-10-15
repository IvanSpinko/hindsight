package org.example.visiontrack;

import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.web.BrowserLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class VisionTrackApplication {

    public static void main(String[] args) {
        String port = System.getProperty("server.port");
        if (alreadyRunning(port)) {
            BrowserLauncher.open("http://127.0.0.1:" + port);
            return;
        }
        SpringApplication.run(VisionTrackApplication.class, args);
    }

    private static boolean alreadyRunning(String port) {
        if (port == null) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(port)), 500);
            return true;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    @Bean
    public ThreadPoolTaskExecutor analysisExecutor(VisionTrackProperties properties) {
        VisionTrackProperties.Jobs jobs = properties.jobs();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("analysis-");
        executor.setCorePoolSize(jobs.concurrency());
        executor.setMaxPoolSize(jobs.concurrency());
        executor.setQueueCapacity(jobs.queueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
