package com.xraymanager;

import com.xraymanager.service.XrayCoreService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class XrayManagerApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx =
            SpringApplication.run(XrayManagerApplication.class, args);

        XrayCoreService xray = ctx.getBean(XrayCoreService.class);

        // Restore system proxy and clean up on any JVM exit (Ctrl+C, SIGTERM, systemctl stop)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { xray.stopProxy();          } catch (Exception ignored) {}
            try { xray.restoreSystemProxy(); } catch (Exception ignored) {}
        }, "shutdown-hook"));
    }
}
