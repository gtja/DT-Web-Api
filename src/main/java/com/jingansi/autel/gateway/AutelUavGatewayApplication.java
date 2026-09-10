package com.jingansi.autel.gateway;

import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(AutelGatewayProperties.class)
public class AutelUavGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutelUavGatewayApplication.class, args);
    }
}

