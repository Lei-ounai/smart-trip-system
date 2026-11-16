package com.smarttrip.mcp;

import com.smarttrip.config.TravelMcpProperties;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class TravelMcpConfiguration {
  public static final String USER="smarttrip.mcp.user";
  public static final String ENDPOINT="/api/mcp";
  @Bean
  org.springframework.beans.factory.SmartInitializingSingleton validateMcpBoundary(TravelMcpProperties p,Environment env){
    return ()->{
      boolean transport=env.getProperty("spring.ai.mcp.server.enabled",Boolean.class,false);
      if(transport!=p.isEnabled())throw new IllegalStateException("MCP business and transport switches must match");
      if(!p.isEnabled())return;
      if(!"STREAMABLE".equals(env.getProperty("spring.ai.mcp.server.protocol"))
          ||!ENDPOINT.equals(env.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint")))throw new IllegalStateException("Only authenticated Streamable MCP endpoint is allowed");
      String address=env.getProperty("server.address","");
      boolean loopback=java.util.Set.of("127.0.0.1","::1","localhost").contains(address);
      boolean privateIp=false;
      if(address.matches("[0-9.]{7,15}"))try{privateIp=java.net.InetAddress.getByName(address).isSiteLocalAddress();}catch(Exception ignored){}
      if(!loopback&&!(p.isAllowPrivateNetwork()&&privateIp))throw new IllegalStateException("MCP requires an explicit loopback bind or approved private address");
      if(p.getRequestsPerMinute()<1||p.getRequestsPerMinute()>120)throw new IllegalStateException("Invalid MCP rate limit");
    };
  }
  @Bean @ConditionalOnProperty(name="smarttrip.mcp.server.enabled",havingValue="true")
  WebMvcStreamableServerTransportProvider securedTravelMcpTransport(JsonMapper mapper){
    return WebMvcStreamableServerTransportProvider.builder().jsonMapper(new JacksonMcpJsonMapper(mapper))
        .mcpEndpoint(ENDPOINT).maxSessions(256).sessionIdleTimeout(java.time.Duration.ofMinutes(10))
        .contextExtractor(request->{
          Object user=request.servletRequest().getAttribute(USER);
          if(!(user instanceof Long id)||id<=0)throw new SecurityException("MCP authentication context missing");
          return McpTransportContext.create(java.util.Map.of(USER,user));
        }).build();
  }
}
