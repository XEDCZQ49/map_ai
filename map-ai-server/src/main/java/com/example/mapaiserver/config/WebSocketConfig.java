package com.example.mapaiserver.config;

import com.example.mapaiserver.asr.ws.OnlineAsrWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final OnlineAsrWebSocketHandler onlineAsrWebSocketHandler;

    public WebSocketConfig(OnlineAsrWebSocketHandler onlineAsrWebSocketHandler) {
        this.onlineAsrWebSocketHandler = onlineAsrWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(onlineAsrWebSocketHandler, "/ws/asr/onlineStream")
                .setAllowedOrigins("*");
    }
}
