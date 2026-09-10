package com.jingansi.autel.gateway.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfiguration {

    @Bean
    @Qualifier("autelRestHttpClient")
    public OkHttpClient autelRestHttpClient(AutelGatewayProperties properties) {
        AutelGatewayProperties.Skycc skycc = properties.getSkycc();
        return new OkHttpClient.Builder()
                .connectTimeout(skycc.getConnectTimeout())
                .readTimeout(skycc.getReadTimeout())
                .callTimeout(skycc.getReadTimeout())
                .build();
    }

    @Bean
    @Qualifier("autelWebSocketHttpClient")
    public OkHttpClient autelWebSocketHttpClient(AutelGatewayProperties properties) {
        AutelGatewayProperties.Skycc skycc = properties.getSkycc();
        return new OkHttpClient.Builder()
                .connectTimeout(skycc.getConnectTimeout())
                .pingInterval(skycc.getWebsocketPingInterval())
                .retryOnConnectionFailure(true)
                .build();
    }
}

