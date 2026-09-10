package com.jingansi.autel.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "autel.gateway.enabled=false")
class AutelUavGatewayApplicationTest {

    @Test
    void contextLoadsWithoutOpeningExternalConnections() {
    }
}
