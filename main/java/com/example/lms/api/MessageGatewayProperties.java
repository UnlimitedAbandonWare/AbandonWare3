package com.example.lms.api;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "kakao")
public class MessageGatewayProperties {
    private String adminKey;
    private String defaultWebUrl;
    private String apiBaseUrl = "https://example.invalid";
    private String sendFriendsMessagePath;
    private String sendMemoPath;
    private String bizTemplateId;
    private String sendBizPath;
    private int webclientConnectTimeoutMs = 5000;
    private int webclientReadTimeoutMs = 10000;
}
