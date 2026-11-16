package com.smarttrip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data @Configuration @ConfigurationProperties(prefix="smarttrip.mcp.server")
public class TravelMcpProperties {
  private boolean enabled;
  private boolean allowPrivateNetwork;
  private int requestsPerMinute=30;
}
