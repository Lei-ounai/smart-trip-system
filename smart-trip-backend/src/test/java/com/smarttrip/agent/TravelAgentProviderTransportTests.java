package com.smarttrip.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.*;
import com.smarttrip.service.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelAgentProviderTransportTests {
  @Test void omittedProviderUsageRemainsUnknownForFinalGeneration()throws Exception{
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{byte[] body="{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"title\\\":\\\"fixture\\\"}\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    try{var ai=new AiProperties();ai.setEnabled(true);ai.setFreeQuotaVerified(true);ai.setApiKey("fixture-only-key");ai.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");var provider=new AiProvider(ai);provider.complete("fixture");var usage=provider.takeDiagnostics();assertNull(usage.get("inputTokens"));assertNull(usage.get("outputTokens"));}
    finally{server.stop(0);}
  }
  @Test void providerFailureIsMappedWithoutHiddenRetries()throws Exception{
    var attempts=new java.util.concurrent.atomic.AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{attempts.incrementAndGet();byte[] body="{\"error\":{\"code\":\"InvalidApiKey\"}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(401,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    var tools=new TravelAgentToolService(mock(GeoService.class));
    try{var ai=new AiProperties();ai.setEnabled(true);ai.setFreeQuotaVerified(true);ai.setApiKey("fixture-only-key");ai.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");var provider=new AiProvider(ai);var c=new TravelAgentExecutionContext("task",7,null,null,new TravelAgentProperties(),new TravelAgentExecutionContext.Observer(){});
      assertEquals(503,assertThrows(com.smarttrip.common.BusinessException.class,()->new TravelAgentOrchestrator(provider,tools).execute(c)).getCode());assertEquals(1,attempts.get());assertEquals(1,c.modelCalls());assertEquals("KEY_INVALID",provider.status());
    }finally{tools.close();server.stop(0);}
  }
  @Test void separateRealSpringAiClientPermitsToolCallsAndPreservesStrictFinalJson()throws Exception{
    var json=new ObjectMapper();var requests=new ArrayList<JsonNode>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{
      var request=json.readTree(exchange.getRequestBody());requests.add(request);
      int n=requests.size();String message;
      if(n==1)message="{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"getTravelKnowledge\",\"arguments\":\"{}\"}}]}";
      else message=json.writeValueAsString(Map.of("role","assistant","content",n==2?TravelAgentOrchestratorTests.SUMMARY:"{\"title\":\"固定最终JSON\"}"));
      String response="{\"id\":\"fixture-completion\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"message\":"+message+",\"finish_reason\":\""+(n==1?"tool_calls":"stop")+"\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
      byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    var tools=new TravelAgentToolService(mock(GeoService.class));
    try{
      var ai=new AiProperties();ai.setEnabled(true);ai.setFreeQuotaVerified(true);ai.setApiKey("fixture-key-not-a-provider-secret");ai.setModel("fixture-model");ai.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
      var provider=new AiProvider(ai);var limits=new TravelAgentProperties();limits.setMaxAgentModelTurns(2);var context=new TravelAgentExecutionContext("task",7,null,null,limits,new TravelAgentExecutionContext.Observer(){});
      assertEquals("真实工具结果已整理",new TravelAgentOrchestrator(provider,tools).execute(context).path("summary").asText());
      assertEquals(2,requests.size());assertFalse(requests.get(0).has("response_format"));assertTrue(requests.get(0).path("tools").isArray());assertEquals(4,requests.get(0).path("tools").size());
      assertTrue(requests.get(1).path("messages").toString().contains("NO_SAVED_KNOWLEDGE"));assertFalse(requests.get(1).has("tools"),"The closing turn must not advertise more tool calls");
      assertTrue(provider.complete("固定测试输入").contains("固定最终JSON"));assertEquals(3,requests.size());assertEquals("json_object",requests.get(2).path("response_format").path("type").asText());assertFalse(requests.get(2).has("tools"));
    }finally{tools.close();server.stop(0);}
  }

  @Test void supplierRejectingToolsCannotBecomeAgentSuccessOrHiddenRetry()throws Exception {
    var attempts=new java.util.concurrent.atomic.AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{attempts.incrementAndGet();byte[] body="{\"error\":{\"code\":\"InvalidParameter\",\"message\":\"Tool calling is not supported\"}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(400,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    var tools=new TravelAgentToolService(mock(GeoService.class));
    try{var ai=new AiProperties();ai.setEnabled(true);ai.setFreeQuotaVerified(true);ai.setApiKey("fixture-only-key");ai.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");var provider=new AiProvider(ai);
      var c=new TravelAgentExecutionContext("task",7,null,null,new TravelAgentProperties(),new TravelAgentExecutionContext.Observer(){});
      assertEquals(502,assertThrows(com.smarttrip.common.BusinessException.class,()->new TravelAgentOrchestrator(provider,tools).execute(c)).getCode());assertEquals(1,attempts.get());assertEquals(1,c.modelCalls());assertTrue(c.traces().isEmpty());assertEquals("REQUEST_REJECTED",provider.status());
    }finally{tools.close();server.stop(0);}
  }
}
