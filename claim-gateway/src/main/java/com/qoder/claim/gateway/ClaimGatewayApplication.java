package com.qoder.claim.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.qoder.claim")
public class ClaimGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimGatewayApplication.class, args);
    }
}