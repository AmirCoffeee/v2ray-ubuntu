package com.xraymanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class XrayManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(XrayManagerApplication.class, args);
    }
}