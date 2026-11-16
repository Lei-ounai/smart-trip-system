package com.smarttrip.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.*;
import com.smarttrip.rag.TravelRagContext;
import com.smarttrip.service.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

class TravelAgentOrchestratorTests {
  GeoService geo;TravelAgentToolService tools;TravelAgentProperties limits;List<Prompt> prompts;Deque<ChatResponse> responses;
  static final String SUMMARY="{\"summary\":\"真实工具结果已整理\",\"destinations\":[],\"sourceIds\":[],\"weatherClaims\":[]}";
  @BeforeEach void setup(){geo=mock(GeoService.class);tools=new TravelAgentToolService(geo);limits=new TravelAgentProperties();prompts=new ArrayList<>();responses=new ArrayDeque<>();}
  @AfterEach void close(){tools.close();}
  static ChatResponse response(String text,List<AssistantMessage.ToolCall> calls){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).toolCalls(calls).build(),ChatGenerationMetadata.builder().finishReason(calls.isEmpty()?"STOP":"TOOL_CALLS").build())),ChatResponseMetadata.builder().usage(new DefaultUsage(10,4)).build());}
  static AssistantMessage.ToolCall call(String id,String name,String args){return new AssistantMessage.ToolCall(id,"function",name,args);}
  TravelAgentOrchestrator orchestrator(){ChatModel model=new ChatModel(){@Override public ChatResponse call(Prompt p){prompts.add(p);return responses.remove();}};var client=ChatClient.create(model);return new TravelAgentOrchestrator(mock(AiProvider.class),tools){@Override protected ChatClient client(Duration timeout){return client;}};}
  TravelAgentExecutionContext context(){return new TravelAgentExecutionContext("task",7L,null,new TravelRagContext("SAVED_RAG_MARKER",Map.of("sources",List.of())),limits,new TravelAgentExecutionContext.Observer(){});}
  @Test void actualManagerExecutesToolAndReturnsItsResultToModel(){responses.add(response("",List.of(call("one","getTravelKnowledge","{}"))));responses.add(response(SUMMARY,List.of()));var c=context();assertEquals("真实工具结果已整理",orchestrator().execute(c).path("summary").asText());assertEquals(2,prompts.size());assertTrue(prompts.get(0).getUserMessage().getText().contains("SAVED_RAG_MARKER"));assertTrue(prompts.get(1).getInstructions().stream().anyMatch(m->m instanceof ToolResponseMessage));assertEquals(1,c.traces().size());assertEquals(2,c.modelCalls());}
  @Test void multipleToolRoundsAndDuplicateRequestsAreLimited(){when(geo.poiAvailable()).thenReturn(true);when(geo.search(anyString(),anyString())).thenReturn(List.of());String args="{\"city\":\"苏州\",\"keyword\":\"园林\"}";responses.add(response("",List.of(call("1","searchAttractions",args),call("2","getTravelKnowledge","{}"))));responses.add(response("",List.of(call("3","searchAttractions",args))));responses.add(response(SUMMARY,List.of()));var c=context();orchestrator().execute(c);assertEquals(3,c.traces().size());verify(geo,times(1)).search(anyString(),anyString());assertEquals("DUPLICATE",c.traces().get(2).status());}
  @Test void lastModelRoundCannotStartMoreTools(){limits.setMaxAgentModelTurns(1);responses.add(response("",List.of(call("1","getTravelKnowledge","{}"))));var c=context();assertThrows(BusinessException.class,()->orchestrator().execute(c));assertEquals(1,prompts.size());assertEquals(0,c.traces().size());}
  @Test void batchExceedingToolLimitIsRejectedBeforeExecution(){limits.setMaxToolCalls(1);responses.add(response("",List.of(call("1","getTravelKnowledge","{}"),call("2","searchAttractions","{}"))));assertThrows(BusinessException.class,()->orchestrator().execute(context()));verifyNoInteractions(geo);}
  @Test void unregisteredToolsCannotExecute(){responses.add(response("",List.of(call("1","saveTrip","{}"))));assertThrows(BusinessException.class,()->orchestrator().execute(context()));verifyNoInteractions(geo);}
  @Test void invalidSummaryDoesNotProduceFakeSuccess(){responses.add(response("not-json",List.of()));assertThrows(BusinessException.class,()->orchestrator().execute(context()));}
  @Test void modelCanFinishWithoutUnnecessaryToolCalls(){responses.add(response(SUMMARY,List.of()));var c=context();orchestrator().execute(c);assertEquals(1,c.modelCalls());assertTrue(c.traces().isEmpty());verifyNoInteractions(geo);}
  @Test void emptyUsageMetadataIsNotRecordedAsZeroCostTokens(){
    responses.add(new ChatResponse(List.of(new Generation(new AssistantMessage(SUMMARY),ChatGenerationMetadata.builder().finishReason("STOP").build()))));
    var recorded=new ArrayList<Map<String,Object>>();var c=new TravelAgentExecutionContext("task",7L,null,null,limits,new TravelAgentExecutionContext.Observer(){@Override public void finishModel(int sequence,Map<String,Object> usage){recorded.add(usage);}});
    orchestrator().execute(c);assertEquals(1,recorded.size());assertFalse(recorded.get(0).containsKey("inputTokens"));assertFalse(recorded.get(0).containsKey("outputTokens"));
  }
  @Test void totalDeadlineStopsBeforeAnyLaterModelOrToolRequest(){
    limits.setMaxExecutionSeconds(1);ChatModel delayed=new ChatModel(){@Override public ChatResponse call(Prompt p){try{Thread.sleep(1100);}catch(InterruptedException e){Thread.currentThread().interrupt();}return response(SUMMARY,List.of());}};
    var client=ChatClient.create(delayed);var orchestrator=new TravelAgentOrchestrator(mock(AiProvider.class),tools){@Override protected ChatClient client(Duration timeout){return client;}};
    var c=context();assertEquals(504,assertThrows(BusinessException.class,()->orchestrator.execute(c)).getCode());assertEquals(1,c.modelCalls());assertEquals(0,c.traces().size());
  }
  @Test void disabledAgentDoesNotTouchProviderStoreOrOrchestrator(){var store=mock(AiTaskStore.class);var provider=mock(AiProvider.class);var orchestrator=mock(TravelAgentOrchestrator.class);var service=new TravelAgentService(limits,new AiProperties(),provider,store,orchestrator,new TravelAgentEvaluator(),tools);assertEquals("DISABLED",service.execute("task",7,null,null).snapshot().get("status"));verifyNoInteractions(provider,store,orchestrator);}
  @Test void configuredButUnverifiedAgentIsNotReady(){limits.setEnabled(true);var ai=new AiProperties();var provider=mock(AiProvider.class);when(provider.available()).thenReturn(true);var service=new TravelAgentService(limits,ai,provider,mock(AiTaskStore.class),mock(TravelAgentOrchestrator.class),new TravelAgentEvaluator(),tools);assertEquals("TOOL_CALLING_UNVERIFIED",service.readiness().get("status"));assertThrows(BusinessException.class,service::requireReady);limits.setToolCallingVerified(true);limits.setVerifiedModel("different-model");assertEquals("TOOL_CALLING_UNVERIFIED",service.readiness().get("status"));}
  @Test void evaluationRejectsChangedHardConditionsAndUnsupportedEvidence(){var r=new ObjectMapper().convertValue(Map.of("destinations",List.of("苏州"),"days",8,"travelers",7,"currency","CNY","budgetScope","PER_PERSON","mustVisit",List.of("园林"),"avoid",List.of("夜爬")),com.smarttrip.entity.TravelRequirement.class);var c=new TravelAgentExecutionContext("task",7,r,null,limits,new TravelAgentExecutionContext.Observer(){});var summary=new ObjectMapper().createObjectNode().put("days",3).put("travelers",2).put("dataKind","REALTIME");summary.putArray("sourceIds").add("invented");summary.putArray("weatherClaims").addObject().put("date",LocalDate.now().plusDays(10).toString());var eval=new TravelAgentEvaluator().evaluate(summary,c);assertEquals("WARNINGS",eval.get("status"));assertTrue(((List<?>)eval.get("warnings")).size()>=7);}

  @Test void presentationOnlyJsonFenceDoesNotNeedAnotherModelCall() {
    responses.add(response("\uFEFF\n```json\n" + SUMMARY + "\n```", List.of()));
    var c=context();assertEquals("真实工具结果已整理",orchestrator().execute(c).path("summary").asText());assertEquals(1,c.modelCalls());
  }
  @Test void jsonFenceDoesNotPermitTrailingProseOrMultipleObjects() {
    for(String text:List.of("```json\n"+SUMMARY+"\n``` additional instructions",SUMMARY+SUMMARY)) {
      responses.add(response(text,List.of()));assertThrows(BusinessException.class,()->orchestrator().execute(context()));
    }
  }
  @Test void modelTimeoutIsBoundedAndDoesNotStartToolsOrRetry() {
    var attempts=new AtomicInteger();ChatModel failed=new ChatModel(){public ChatResponse call(Prompt p){attempts.incrementAndGet();throw new RuntimeException(new java.net.SocketTimeoutException("fixture timeout"));}};
    var client=ChatClient.create(failed);var c=context();var agent=new TravelAgentOrchestrator(mock(AiProvider.class),tools){protected ChatClient client(Duration timeout){return client;}};
    assertEquals(504,assertThrows(BusinessException.class,()->agent.execute(c)).getCode());assertEquals(1,attempts.get());assertTrue(c.traces().isEmpty());verifyNoInteractions(geo);
  }
  @Test void failureAfterAnActualToolRoundDoesNotReplayThatTool() {
    when(geo.poiAvailable()).thenReturn(true);when(geo.search(anyString(),anyString())).thenReturn(List.of());
    var attempts=new AtomicInteger();ChatModel failed=new ChatModel(){public ChatResponse call(Prompt p){if(attempts.incrementAndGet()==1)return response("",List.of(TravelAgentOrchestratorTests.call("one","searchAttractions","{\"city\":\"苏州\",\"keyword\":\"园林\"}")));throw new IllegalStateException("fixture upstream failure");}};
    var client=ChatClient.create(failed);var c=context();var agent=new TravelAgentOrchestrator(mock(AiProvider.class),tools){protected ChatClient client(Duration timeout){return client;}};
    assertEquals(502,assertThrows(BusinessException.class,()->agent.execute(c)).getCode());assertEquals(2,attempts.get());assertEquals(1,c.traces().size());verify(geo,times(1)).search("苏州","园林");
  }
  @Test void interruptionStopsBeforeAnyModelOrToolRequest() {
    var c=context();Thread.currentThread().interrupt();try{assertEquals(504,assertThrows(BusinessException.class,()->orchestrator().execute(c)).getCode());assertEquals(0,c.modelCalls());verifyNoInteractions(geo);}finally{Thread.interrupted();}
  }


  @Test void lastAllowedModelRoundClosesWithoutAdvertisingMoreTools() {
    limits.setMaxAgentModelTurns(2);
    responses.add(response("",List.of(call("one","getTravelKnowledge","{}"))));responses.add(response(SUMMARY,List.of()));
    var c=context();orchestrator().execute(c);assertEquals(2,c.modelCalls());
    // Wire-level tool removal is asserted by TravelAgentProviderTransportTests; generic ChatModel options are normalized by ChatClient.
    assertEquals(1,c.traces().size());assertTrue(prompts.get(1).getInstructions().stream().anyMatch(m->m instanceof SystemMessage&&m.getText().contains("本轮不能调用工具")));
  }
  @Test void budgetSchemaExplainsItsExactScopeAndCurrencyContract() {
    var schema=orchestrator().callbacks(context()).stream().filter(t->t.getToolDefinition().name().equals("calculateBudget")).findFirst().orElseThrow().getToolDefinition().inputSchema();
    assertTrue(schema.contains("TOTAL"));assertTrue(schema.contains("PER_PERSON"));assertTrue(schema.contains("CNY"));
  }
}
