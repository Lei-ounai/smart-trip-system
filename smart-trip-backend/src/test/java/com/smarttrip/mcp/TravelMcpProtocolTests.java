package com.smarttrip.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.smarttrip.agent.*;
import com.smarttrip.common.*;
import com.smarttrip.config.*;
import com.smarttrip.entity.User;
import com.smarttrip.mapper.UserMapper;
import com.smarttrip.service.*;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** Real embedded HTTP + official independent MCP SDK client; JWT is real, external stores/providers are fixtures. */
class TravelMcpProtocolTests {
  static ConfigurableApplicationContext app;static String base,token,other;static UserMapper users;static StringRedisTemplate redis;
  @TestConfiguration
  @EnableAutoConfiguration(excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration","org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
      "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"})
  @Import({TravelMcpConfiguration.class,TravelMcpTools.class,TravelMcpSecurityFilter.class,JwtAuthInterceptor.class,TravelMcpProperties.class,TravelAgentProperties.class})
  static class App {
    @Bean UserMapper users(){var mapper=mock(UserMapper.class);when(mapper.findById(anyLong())).thenAnswer(i->{var u=new User();u.setId(i.getArgument(0));u.setStatus(1);u.setTokenVersion(0);return u;});return mapper;}
    @Bean StringRedisTemplate redis(){var r=mock(StringRedisTemplate.class);when(r.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);return r;}
    @Bean GeoService geo(){var service=mock(GeoService.class);when(service.weatherStatus()).thenReturn("DISABLED");return service;}
    @Bean TravelAgentToolService tools(GeoService geo){return new TravelAgentToolService(geo);}
    @Bean AiTaskStore store(){return mock(AiTaskStore.class);}
  }
  static ConfigurableApplicationContext start(boolean enabled){
    String previous=System.getProperty("org.springframework.boot.logging.LoggingSystem");
    System.setProperty("org.springframework.boot.logging.LoggingSystem","none");
    try{return new SpringApplicationBuilder(App.class).run("--server.address=127.0.0.1","--server.port=0",
      "--spring.config.import=optional:file:./does-not-exist-agent-test.yml","--spring.flyway.enabled=false","--smarttrip.agent.enabled=false",
      "--smarttrip.mcp.server.enabled="+enabled,"--spring.ai.mcp.server.enabled="+enabled,"--spring.ai.mcp.server.protocol=STREAMABLE",
      "--spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp","--logging.level.root=WARN");
    }finally{if(previous==null)System.clearProperty("org.springframework.boot.logging.LoggingSystem");else System.setProperty("org.springframework.boot.logging.LoggingSystem",previous);}
  }
  @BeforeAll static void setup(){JwtUtil.configure("mcp-protocol-fixture-secret-never-used-outside-tests");token=JwtUtil.generateToken(7L,"fixture-owner");other=JwtUtil.generateToken(8L,"fixture-other");app=start(true);base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();users=app.getBean(UserMapper.class);redis=app.getBean(StringRedisTemplate.class);}
  @AfterAll static void close(){if(app!=null)app.close();}
  McpSyncClient client(String jwt){return McpClient.sync(HttpClientStreamableHttpTransport.builder(base).endpoint("/api/mcp")
      .requestBuilder(HttpRequest.newBuilder().header("Authorization","Bearer "+jwt)).openConnectionOnStartup(false).build())
      .requestTimeout(Duration.ofSeconds(8)).build();}
  int post(String auth,String session,String body)throws Exception{var builder=HttpRequest.newBuilder(URI.create(base+"/api/mcp"))
      .header("Content-Type","application/json").header("Accept","application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body));
    if(auth!=null)builder.header("Authorization","Bearer "+auth);if(session!=null)builder.header("Mcp-Session-Id",session);
    return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
  @Test void independentClientDiscoversOnlyFourReadOnlyToolsAndCallsActualJavaBudget(){
    try(var client=client(token)){
      client.initialize();var list=client.listTools().tools();assertEquals(Set.of("searchAttractions","getWeatherForecast","calculateBudget","getTravelKnowledge"),list.stream().map(McpSchema.Tool::name).collect(java.util.stream.Collectors.toSet()));
      for(var tool:list){assertTrue(tool.annotations().readOnlyHint());assertFalse(tool.annotations().destructiveHint());}
      var input=new LinkedHashMap<String,Object>();input.put("travelers",7);input.put("days",8);input.put("currency","CNY");input.put("costScope","TOTAL");input.put("transport",100);input.put("accommodation",200);input.put("food",300);input.put("tickets",0);input.put("other",0);
      var result=client.callTool(new McpSchema.CallToolRequest("calculateBudget",Map.of("input",input)));
      assertFalse(Boolean.TRUE.equals(result.isError()),result.content().toString());String text=result.content().toString();assertTrue(text.contains("600"),text);assertTrue(text.contains("ESTIMATE"),text);
      var weather=client.callTool(new McpSchema.CallToolRequest("getWeatherForecast",Map.of("input",Map.of("city","苏州","date",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(6).toString()))));assertTrue(weather.content().toString().contains("OUTSIDE_FORECAST_WINDOW"));
      var bad=client.callTool(new McpSchema.CallToolRequest("calculateBudget",Map.of("input",Map.of("travelers",0,"days",8,"currency","CNY","costScope","TOTAL"))));assertTrue(Boolean.TRUE.equals(bad.isError())||bad.content().toString().contains("INVALID_ARGUMENT"));
    }
    verify(app.getBean(AiTaskStore.class),never()).beginModelCall(anyString(),anyString(),anyInt());verify(app.getBean(GeoService.class),never()).weather(any(),any());
  }
  @Test void anonymousInvalidJwtAndOtherUsersSessionAreDenied()throws Exception{
    assertEquals(401,post(null,null,"{}"));assertEquals(401,post("invalid",null,"{}"));
    String init="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}";
    var request=HttpRequest.newBuilder(URI.create(base+"/api/mcp")).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Accept","application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(init)).build();
    var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());var session=response.headers().firstValue("mcp-session-id").orElseThrow();
    assertEquals(403,post(other,session,"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
  }
  @Test void rateLimitAndRedisFailureFailClosed()throws Exception{
    try{when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(31L);assertEquals(429,post(token,null,"{}"));
      when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("fixture"));assertEquals(503,post(token,null,"{}"));
    }finally{when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);}
  }
  @Test void mcpDisabledDoesNotExposeEndpoint()throws Exception{
    try(var disabled=start(false)){int port=((WebServerApplicationContext)disabled).getWebServer().getPort();var result=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/mcp")).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding());assertEquals(404,result.statusCode());assertTrue(disabled.getBeansOfType(TravelMcpTools.class).isEmpty());}
  }
  @Test void knowledgeToolUsesTransportIdentityAndOnlyPublicSavedSourceFields(){
    var store=app.getBean(AiTaskStore.class);String id="11111111-1111-4111-8111-111111111111";
    String context=com.smarttrip.rag.RagContextBuilder.RULES+"\n{\"sources\":[{\"chunkId\":\"public-k1\",\"content\":\"已有公共资料摘录\",\"sourceName\":\"公共来源\",\"sourceUrl\":\"https://example.org/travel\"}]}";
    when(store.existingRagContext(id)).thenAnswer(i->{assertEquals(7L,UserContext.getUserId());return new com.smarttrip.rag.TravelRagContext(context,Map.of("sources",List.of(Map.of("chunkId","public-k1")),"privateTaskValue","must-not-return"));});
    try(var client=client(token)){
      client.initialize();var result=client.callTool(new McpSchema.CallToolRequest("getTravelKnowledge",Map.of("input",Map.of("taskId",id))));
      assertFalse(Boolean.TRUE.equals(result.isError()),result.content().toString());assertTrue(result.content().toString().contains("已有公共资料摘录"));assertFalse(result.content().toString().contains("must-not-return"));assertFalse(result.content().toString().contains("_ragContext"));
    }
    doThrow(new BusinessException(404,"任务不存在")).when(store).existingRagContext(id);
    try(var client=client(other)){client.initialize();var result=client.callTool(new McpSchema.CallToolRequest("getTravelKnowledge",Map.of("input",Map.of("taskId",id))));assertTrue(Boolean.TRUE.equals(result.isError()));assertFalse(result.content().toString().contains("已有公共资料摘录"));}
  }
  @Test void browserOriginIsRejectedAndRevokedJwtIsNotAccepted()throws Exception{
    var request=HttpRequest.newBuilder(URI.create(base+"/api/mcp")).header("Origin","https://untrusted.example").header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString("{}")).build();assertEquals(403,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
    try{when(users.findById(7L)).thenAnswer(i->{var u=new User();u.setId(7L);u.setStatus(1);u.setTokenVersion(1);return u;});assertEquals(401,post(token,null,"{}"));}
    finally{when(users.findById(7L)).thenAnswer(i->{var u=new User();u.setId(7L);u.setStatus(1);u.setTokenVersion(0);return u;});}
  }
  @Test void startupGuardRejectsUnprotectedTransportOrPublicBindWithoutOpeningSocket(){
    var config=new TravelMcpConfiguration();var p=new TravelMcpProperties();p.setEnabled(true);
    var env=new org.springframework.mock.env.MockEnvironment().withProperty("spring.ai.mcp.server.enabled","true")
        .withProperty("spring.ai.mcp.server.protocol","STREAMABLE").withProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint","/api/mcp").withProperty("server.address","0.0.0.0");
    assertThrows(IllegalStateException.class,()->config.validateMcpBoundary(p,env).afterSingletonsInstantiated());
    env.withProperty("server.address","127.0.0.1");assertDoesNotThrow(()->config.validateMcpBoundary(p,env).afterSingletonsInstantiated());
    env.withProperty("spring.ai.mcp.server.enabled","false");assertThrows(IllegalStateException.class,()->config.validateMcpBoundary(p,env).afterSingletonsInstantiated());
  }
}
