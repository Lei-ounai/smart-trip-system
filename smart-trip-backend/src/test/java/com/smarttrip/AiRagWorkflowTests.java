package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.BusinessException;
import com.smarttrip.common.UserContext;
import com.smarttrip.config.AiProperties;
import com.smarttrip.config.RagProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripWriteDTO;
import com.smarttrip.rag.TravelRagContext;
import com.smarttrip.rag.TravelRagService;
import com.smarttrip.rag.knowledge.RagException;
import com.smarttrip.repository.TripRepository;
import com.smarttrip.service.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Offline workflow checks: the provider transport, database and Redis never leave this JVM. */
class AiRagWorkflowTests {
  private static final String ID = "rag-generation-task";
  private static final String INPUT = "已确认：苏州一天，预算总额500 CNY，轻松参观园林。";
  private static final String PRIVATE_REFERENCE = "RAG_PRIVATE_REFERENCE_MARKER";
  private static final String RESPONSE = """
      {"title":"苏州慢游","destination":"苏州","summary":"参观苏州园林，费用仅为估算。",
       "days":[{"dayTheme":"园林文化","items":[
       {"itemName":"苏州园林","itemType":"SCENIC","startTime":"09:00",
        "costTimeHours":2,"costEstimate":100}]}]}
      """;

  private final ObjectMapper mapper = new ObjectMapper();
  private JdbcTemplate db;
  private WorkbenchService trips;
  private ResourceAccess access;
  private AiProvider provider;
  private AiTaskStore store;
  private AiTaskService service;
  private TravelRequirementService requirements;
  private TravelRagService rag;
  private RagProperties properties;
  private Map<String, Object> row;
  private TripDetailVO saved;
  private boolean failSnapshotWrite;
  private final List<com.smarttrip.agent.TravelAgentToolService> agentTools=new ArrayList<>();

  @BeforeEach
  void setup() {
    db = mock(JdbcTemplate.class);
    access = mock(ResourceAccess.class);
    when(access.userId()).thenReturn(7L);
    trips = spy(new WorkbenchService(db, mock(TripRepository.class), access));
    requirements = new TravelRequirementService();
    // The real parser remains exercised. Both methods capable of model requests are stubbed.
    provider = spy(new AiProvider(new AiProperties()));
    doReturn(RESPONSE).when(provider).complete(anyString());
    doReturn(requirement()).when(provider).interpret(anyString());
    doReturn(true).when(provider).available();
    store = spy(new AiTaskStore(db, trips, access, mock(ItineraryAnalysisService.class),
        requirements, new AiDraftNormalizer(), provider));
    var redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service = new AiTaskService(db, provider, store, trips, access, redis, transactions,
        mock(ItineraryAnalysisService.class), requirements);
    rag = mock(TravelRagService.class);
    properties = new RagProperties();
    service.configureRag(rag, properties);

    row = new LinkedHashMap<>();
    row.put("id", ID);
    row.put("user_id", 7L);
    row.put("task_type", "GENERATE");
    row.put("status", "QUEUED");
    row.put("quota_reserved", 0);
    row.put("quota_reserved_date", java.sql.Date.valueOf(LocalDate.now()));
    row.put("proposal_meta_json", trips.encode(Map.of(
        "requirement", requirement(), "expiresAt", Instant.now().plusSeconds(86400).toString(),
        "phase", "QUEUED", "phaseHistory",
        List.of(Map.of("phase", "QUEUED", "at", Instant.now().minusSeconds(1).toString())))));
    when(db.queryForMap(anyString(), any(Object[].class))).thenAnswer(invocation -> row);
    when(db.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.equals("SELECT id FROM sys_user WHERE id=? FOR UPDATE")) return List.of(Map.of("id", 7L));
      if (sql.contains("status='RUNNING' AND id<>?")) {
        boolean otherRunning = "RUNNING".equals(row.get("status"))
            && Objects.equals(row.get("user_id"), invocation.getArgument(1))
            && !Objects.equals(row.get("id"), invocation.getArgument(2));
        return otherRunning ? List.of(row) : List.of();
      }
      if (!sql.contains("AS userId")) return List.of(row);
      if (!Objects.equals(access.userId(), row.get("user_id"))) return List.of();
      var view = new LinkedHashMap<String, Object>();
      view.put("id", ID);
      view.put("userId", row.get("user_id"));
      view.put("status", row.get("status"));
      view.put("type", row.get("task_type"));
      view.put("tripId", row.get("trip_id"));
      view.put("resultJson", row.get("result_json"));
      view.put("proposalMeta", row.get("proposal_meta_json"));
      view.put("errorMessage", row.get("error_message"));
      return List.of(view);
    });
    when(db.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("SET proposal_meta_json=?")) {
        String metadata = invocation.getArgument(1);
        if (failSnapshotWrite && sql.contains("AND status='RUNNING'")
            && metadata.contains("_ragContext"))
          throw new DataAccessResourceFailureException("synthetic snapshot persistence failure");
        row.put("proposal_meta_json", metadata);
      } else if (sql.contains("status='RUNNING'")) {
        row.put("status", "RUNNING");
        row.put("quota_reserved", 1);
      } else if (sql.contains("status='PREVIEW'")) {
        row.put("status", "PREVIEW");
        row.put("result_json", invocation.getArgument(1));
        row.put("proposal_meta_json", invocation.getArgument(2));
        row.put("quota_reserved", 0);
      } else if (sql.contains("SET result_json=?,proposal_meta_json=?")) {
        row.put("result_json", invocation.getArgument(1));
        row.put("proposal_meta_json", invocation.getArgument(2));
      } else if (sql.contains("status='APPLIED'")) {
        row.put("status", "APPLIED");
        row.put("trip_id", invocation.getArgument(1));
        row.put("result_json", invocation.getArgument(2));
      } else if (sql.contains("status='FAILED'")) {
        row.put("status", "FAILED");
        row.put("quota_reserved", 0);
        row.put("error_message", invocation.getArgument(1));
      } else if (sql.contains("status='SUCCEEDED'")) {
        row.put("status", "SUCCEEDED");
        row.put("result_json", invocation.getArgument(1));
        row.put("quota_reserved", 0);
      }
      return 1;
    });
    doAnswer(invocation -> {
      saved = trips.decode(trips.encode(invocation.getArgument(0)));
      saved.setId(91L);
      saved.setVersion(0L);
      return saved;
    }).when(trips).createGenerated(any());
    doAnswer(invocation -> saved).when(trips).load(91L);
  }

  @AfterEach
  void close() {
    service.shutdown();
    agentTools.forEach(com.smarttrip.agent.TravelAgentToolService::close);
    UserContext.clear();
  }

  private TravelRequirement requirement() {
    return mapper.convertValue(Map.of("description", "去苏州一天",
        "destinations", List.of("苏州"), "days", 1, "currency", "CNY",
        "budgetScope", "TOTAL", "budget", 500), TravelRequirement.class);
  }

  private TravelRagContext reference() {
    return new TravelRagContext(PRIVATE_REFERENCE + "：苏州园林文化参考。", Map.of(
        "status", "COMPLETED", "ragEnabled", true, "finalContextCount", 1,
        "sources", List.of(Map.of("documentId", "travel-suzhou-gardens-v1",
            "title", "苏州古典园林", "sourceUrl", "https://whc.unesco.org/en/list/813/")),
        "candidates", List.of(Map.of("documentId", "travel-suzhou-gardens-v1"))));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> metadata() {
    return (Map<String, Object>) trips.parseObject((String) row.get("proposal_meta_json"));
  }

  private void replaceMetadata(Map<String, Object> metadata) {
    row.put("proposal_meta_json", trips.encode(metadata));
  }

  private List<String> phases() {
    return ((List<?>) metadata().get("phaseHistory")).stream()
        .map(value -> ((Map<?, ?>) value).get("phase").toString()).toList();
  }

  private void runGeneration() {
    ReflectionTestUtils.invokeMethod(service, "run", ID, 7L, INPUT, null, false, true);
  }

  private void enableRag() {
    properties.setEnabled(true);
    when(rag.retrieve(any(TravelRequirement.class), eq(ID))).thenReturn(reference());
  }

  private TripDetailVO editableDraft() {
    return mapper.convertValue(trips.parseObject((String) row.get("result_json")),
        TripWriteDTO.class).toTrip();
  }

  @Test
  void disabledRagKeepsOriginalModelFlowAndPhaseHistory() {
    runGeneration();
    assertEquals("PREVIEW", row.get("status"));
    assertEquals(List.of("QUEUED", "MODEL", "MODEL_COMPLETED", "NORMALIZING", "VALIDATING", "PREVIEW"),
        phases());
    verifyNoInteractions(rag);
    verify(store).start(ID);
    verify(store, never()).start(ID, true);
    verify(provider).complete(INPUT);
    verify(provider).parseGeneration(RESPONSE);
    verify(trips, never()).createGenerated(any());
    assertFalse(metadata().containsKey("rag"));
  }

  @Test
  void enabledRagPersistsSnapshotBeforeTheOnlyProviderRequest() {
    enableRag();
    doAnswer(invocation -> {
      assertEquals(reference().promptContext(), metadata().get("_ragContext"));
      assertEquals(1, ((Number) ((Map<?, ?>) metadata().get("rag")).get("finalContextCount")).intValue());
      assertEquals("MODEL", metadata().get("phase"));
      assertTrue(invocation.getArgument(0, String.class).contains(PRIVATE_REFERENCE));
      return RESPONSE;
    }).when(provider).complete(anyString());
    runGeneration();

    var order = inOrder(store, rag, provider);
    order.verify(store).start(ID, true);
    order.verify(store).existingRagContext(ID);
    order.verify(store).retrievalRequirement(ID);
    order.verify(rag).retrieve(eq(requirement()), eq(ID));
    order.verify(store).recordRagContext(ID, reference());
    order.verify(store).setPhase(ID, "MODEL");
    order.verify(provider).complete(INPUT + "\n\n" + reference().promptContext());
    assertEquals("PREVIEW", row.get("status"));
    assertEquals(List.of("QUEUED", "RETRIEVING", "MODEL", "MODEL_COMPLETED", "NORMALIZING",
        "VALIDATING", "PREVIEW"), phases());
    verify(provider, times(1)).complete(anyString());
    verify(rag, times(1)).retrieve(any(), eq(ID));
    verify(db, times(1)).update(contains("ai_quota_used=ai_quota_used+1"), eq(7L));
  }

  @Test
  void existingSnapshotIsReusedAndTerminalWorkerCannotCallTheModelAgain() {
    properties.setEnabled(true);
    var stored = metadata();
    stored.put("_ragContext", reference().promptContext());
    stored.put("rag", reference().snapshot());
    replaceMetadata(stored);
    runGeneration();
    runGeneration();
    verifyNoInteractions(rag);
    verify(store, never()).recordRagContext(anyString(), any());
    verify(provider, times(1)).complete(INPUT + "\n\n" + reference().promptContext());
    verify(db, times(1)).update(contains("ai_quota_used=ai_quota_used+1"), eq(7L));
    assertEquals("PREVIEW", row.get("status"));
  }

  @Test
  void firstSnapshotWinsAndPublicTaskReadsNeverExposeThePrivateContext() {
    row.put("status", "RUNNING");
    row.put("quota_reserved", 1);
    var first = store.recordRagContext(ID, reference());
    var later = store.recordRagContext(ID, new TravelRagContext("different unaccepted text",
        Map.of("status", "FALLBACK", "sources", List.of(), "finalContextCount", 0)));
    assertEquals(first, later);
    assertEquals(first, store.existingRagContext(ID));
    verify(db, times(1)).update(contains("AND status='RUNNING'"), anyString(), eq(ID), eq(7L));
    var view = store.get(ID);
    var generation = (Map<?, ?>) view.get("generation");
    assertEquals(first.snapshot(), generation.get("rag"));
    assertFalse(generation.containsKey("_ragContext"));
    assertFalse(trips.encode(view).contains(PRIVATE_REFERENCE));
    assertEquals(first.promptContext(), metadata().get("_ragContext"));
    verifyNoInteractions(rag);
    verify(provider, never()).complete(anyString());
  }

  @Test
  void snapshotStorageFailurePreventsModelAndRefundsOnlyOnce() {
    enableRag();
    failSnapshotWrite = true;
    runGeneration();
    assertEquals("FAILED", row.get("status"));
    assertTrue(row.get("error_message").toString().contains("尚未发起模型生成"));
    assertFalse(metadata().containsKey("_ragContext"));
    verify(provider, never()).complete(anyString());
    verify(store).fail(eq(ID), contains("快照保存失败"));
    verify(db, times(1)).update(contains("ai_quota_used=GREATEST"), eq(7L), any());
    store.fail(ID, "重复失败通知");
    runGeneration();
    verify(db, times(1)).update(contains("ai_quota_used=GREATEST"), eq(7L), any());
    verify(rag, times(1)).retrieve(any(), eq(ID));
  }

  @Test
  void strictRetrievalFailurePersistsSanitizedStatusAndDoesNotCallProvider() {
    enableRag();
    properties.setFallbackToOriginal(false);
    when(rag.retrieve(any(), eq(ID)))
        .thenThrow(new RagException("ELASTICSEARCH_UNAVAILABLE", "sanitized fixture failure"));
    runGeneration();
    assertEquals("FAILED", row.get("status"));
    String error = row.get("error_message").toString();
    assertTrue(error.contains("ELASTICSEARCH_UNAVAILABLE"));
    assertTrue(error.contains("尚未发起模型生成"));
    var snapshot = (Map<?, ?>) metadata().get("rag");
    assertEquals("FAILED", snapshot.get("status"));
    assertEquals("ELASTICSEARCH_UNAVAILABLE", snapshot.get("fallbackReason"));
    assertEquals(0, ((Number) snapshot.get("finalContextCount")).intValue());
    verify(provider, never()).complete(anyString());
    verify(db).update(contains("ai_quota_used=GREATEST"), eq(7L), any());
    assertFalse(trips.encode(store.get(ID)).contains("_ragContext"));
  }

  @Test
  void allowedFallbackUsesOriginalPromptWithHonestEmptySourceSnapshot() {
    enableRag();
    var fallback = new TravelRagContext("", Map.of("status", "FALLBACK", "ragEnabled", true,
        "fallbackReason", "KNOWLEDGE_INDEX_EMPTY", "finalContextCount", 0,
        "sources", List.of(), "candidates", List.of()));
    when(rag.retrieve(any(), eq(ID))).thenReturn(fallback);
    runGeneration();
    assertEquals("PREVIEW", row.get("status"));
    verify(provider).complete(INPUT);
    assertEquals(fallback.snapshot(), metadata().get("rag"));
    assertEquals("", metadata().get("_ragContext"));
    assertTrue(((List<?>) ((Map<?, ?>) metadata().get("rag")).get("sources")).isEmpty());
  }

  @Test
  void modifyTasksBypassRagEvenWhenEnabled() {
    properties.setEnabled(true);
    row.put("task_type", "MODIFY");
    // This assertion concerns task dispatch; existing modification validation has its own tests.
    doNothing().when(store).complete(eq(ID), any(TripDetailVO.class));
    ReflectionTestUtils.invokeMethod(service, "run", ID, 7L, INPUT, null, false, false);
    verifyNoInteractions(rag);
    verify(store).start(ID);
    verify(store, never()).existingRagContext(anyString());
    verify(provider).complete(INPUT);
    verify(store).complete(eq(ID), any());
  }

  @Test
  void interpretationTasksBypassRagAndTripGeneration() {
    properties.setEnabled(true);
    row.put("task_type", "INTERPRET");
    String input = trips.encode(Map.of("requirement", requirement(), "editedFields", List.of()));
    ReflectionTestUtils.invokeMethod(service, "run", ID, 7L, input, null, true, false);
    verifyNoInteractions(rag);
    verify(provider).interpret(requirement().description());
    verify(provider, never()).complete(anyString());
    verify(store).completeInterpretation(eq(ID), anyMap());
    assertEquals("SUCCEEDED", row.get("status"));
  }

  @Test
  void previewEditingValidationApplyAndReadbackDoNotRepeatRetrievalOrModelCalls() {
    enableRag();
    runGeneration();
    var initial = store.get(ID);
    var snapshot = ((Map<?, ?>) initial.get("generation")).get("rag");
    assertEquals(initial, store.get(ID));
    assertEquals("PREVIEW", store.recoverDraft(ID).get("status"));

    var invalidEdit = editableDraft();
    invalidEdit.getDays().get(0).getItems().get(0).setCostEstimate(new BigDecimal("600"));
    invalidEdit.getDays().get(0).getItems().get(0).setCostTimeHours(null);
    var blocked = store.updateDraft(ID, invalidEdit, 0L);
    var issues = mapper.valueToTree(((Map<?, ?>) blocked.get("generation")).get("issues"))
        .findValuesAsText("code");
    assertTrue(issues.contains("BUDGET_EXCEEDED"), issues.toString());
    assertTrue(issues.contains("MISSING_DURATION"), issues.toString());
    assertEquals(422, assertThrows(BusinessException.class, () -> store.apply(ID, null, 1L)).getCode());
    verify(trips, never()).createGenerated(any());

    var repaired = trips.decode(RESPONSE);
    repaired.setTitle("我编辑的苏州草稿");
    repaired.getDays().get(0).getItems().get(0).setCostEstimate(new BigDecimal("150"));
    var edited = store.updateDraft(ID, repaired, 1L);
    assertEquals(snapshot, ((Map<?, ?>) edited.get("generation")).get("rag"));
    assertEquals(409, assertThrows(BusinessException.class, () -> store.apply(ID, null, 1L)).getCode());
    assertEquals(91L, store.apply(ID, null, 2L).getId());
    assertEquals(91L, store.apply(ID, null, 0L).getId());
    assertEquals("我编辑的苏州草稿", saved.getTitle());
    assertEquals(0, saved.getTotalBudget().compareTo(new BigDecimal("150")));
    assertEquals("APPLIED", store.get(ID).get("status"));
    assertEquals(snapshot, ((Map<?, ?>) store.get(ID).get("generation")).get("rag"));
    assertFalse(trips.encode(store.get(ID)).contains(PRIVATE_REFERENCE));
    verify(trips, times(1)).createGenerated(any());
    verify(rag, times(1)).retrieve(any(), eq(ID));
    verify(provider, times(1)).complete(anyString());
    verify(db, times(1)).update(contains("ai_quota_used=ai_quota_used+1"), eq(7L));
    verify(db, never()).update(contains("ai_quota_used=GREATEST"), any(Object[].class));
  }

  @Test
  void repeatedConfirmedSubmissionAndReadRecoveryReturnExistingTaskWithoutNewWork() {
    enableRag();
    runGeneration();
    row.put("request_hash", CommunityService.hash("GENERATE\nnull\n"
        + trips.encode(requirements.confirmed(requirement()))));
    assertEquals(ID, service.submitGeneration("same-request-key", requirement(), true).get("id"));
    assertEquals(ID, service.submitGeneration("same-request-key", requirement(), true).get("id"));
    assertEquals("PREVIEW", store.get(ID).get("status"));
    verify(rag, times(1)).retrieve(any(), eq(ID));
    verify(provider, times(1)).complete(anyString());
    verify(db, times(1)).update(contains("ai_quota_used=ai_quota_used+1"), eq(7L));
    verify(db, never()).update(startsWith("INSERT INTO"), any(Object[].class));
  }
  private com.smarttrip.agent.TravelAgentService enableAgent(boolean fail) {
    var limits=new com.smarttrip.config.TravelAgentProperties();limits.setEnabled(true);limits.setToolCallingVerified(true);
    var ai=new AiProperties();limits.setVerifiedModel(ai.getModel());limits.setVerifiedBaseUrl(ai.getBaseUrl());
    var tools=new com.smarttrip.agent.TravelAgentToolService(mock(GeoService.class));agentTools.add(tools);
    var counter=new java.util.concurrent.atomic.AtomicInteger();
    org.springframework.ai.chat.model.ChatModel fixed=new org.springframework.ai.chat.model.ChatModel(){
      @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt){
        assertEquals("RAG_AGENT_MARKER",mapper.valueToTree(metadata().get("rag")).path("fixture").asText());
        assertTrue(metadata().containsKey("_ragContext"));
        if(fail)throw new BusinessException(502,"固定Agent响应失败夹具");
        int n=counter.incrementAndGet();
        org.springframework.ai.chat.messages.AssistantMessage message;
        if(n==1)message=org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
            .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("read-knowledge","function","getTravelKnowledge","{}"))).build();
        else {
          assertTrue(prompt.getInstructions().stream().anyMatch(m->m instanceof org.springframework.ai.chat.messages.ToolResponseMessage response&&response.getResponses().toString().contains("Agent资料原文")));
          message=new org.springframework.ai.chat.messages.AssistantMessage("{\"summary\":\"已读取已有知识快照\",\"destinations\":[\"苏州\"],\"days\":1,\"travelers\":null,\"budgetScope\":\"TOTAL\",\"currency\":\"CNY\",\"mustVisit\":[],\"avoid\":[],\"sourceIds\":[\"k1\"],\"weatherClaims\":[]}");
        }
        return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(message,
            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(n==1?"TOOL_CALLS":"STOP").build())),
            org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().usage(new org.springframework.ai.chat.metadata.DefaultUsage(12,6)).build());
      }
    };
    var client=org.springframework.ai.chat.client.ChatClient.create(fixed);
    var orchestrator=new com.smarttrip.agent.TravelAgentOrchestrator(provider,tools){
      @Override protected org.springframework.ai.chat.client.ChatClient client(Duration timeout){return client;}
    };
    var agent=new com.smarttrip.agent.TravelAgentService(limits,ai,provider,store,orchestrator,new com.smarttrip.agent.TravelAgentEvaluator(),tools);
    service.configureAgent(agent);
    var source=Map.of("chunkId","k1","documentId","public-doc","title","公共知识","sourceName","公开来源","sourceUrl","https://example.org/travel","content","Agent资料原文");
    var context=new TravelRagContext(com.smarttrip.rag.RagContextBuilder.RULES+"\n"+trips.encode(Map.of("sources",List.of(source))),
        Map.of("fixture","RAG_AGENT_MARKER","sources",List.of(source),"status","COMPLETED"));
    properties.setEnabled(true);when(rag.retrieve(any(),eq(ID))).thenReturn(context);
    return agent;
  }

  @Test void agentUsesPersistedRagActualToolResultsAndKeepsEditableSaveClosure() {
    enableAgent(false);
    doAnswer(invocation->{String prompt=invocation.getArgument(0);assertTrue(prompt.contains("Agent资料原文"));assertTrue(prompt.contains("AGENT_REFERENCE_DATA"));assertTrue(prompt.contains("toolResults"));assertEquals("COMPLETED",mapper.valueToTree(metadata().get("agent")).path("status").asText());return RESPONSE;}).when(provider).complete(anyString());
    runGeneration();assertEquals("PREVIEW",row.get("status"));
    assertEquals(3,((List<?>)metadata().get("modelCalls")).size());
    var lease=org.mockito.ArgumentCaptor.forClass(Duration.class);
    var lockRedis=(StringRedisTemplate)ReflectionTestUtils.getField(service,"redis");
    verify(lockRedis.opsForValue()).setIfAbsent(eq("smarttrip:ai:user:7"),eq(ID),lease.capture());
    assertTrue(lease.getValue().getSeconds()>=276);assertTrue(lease.getValue().getSeconds()<=900);
    var usage=mapper.valueToTree(metadata().get("modelUsage"));assertEquals(24,usage.path("knownInputTokens").asInt());assertEquals(12,usage.path("knownOutputTokens").asInt());assertFalse(usage.path("tokensComplete").asBoolean());assertTrue(usage.path("estimatedCost").isNull());
    assertFalse(trips.encode(store.get(ID)).contains("_agentContext"));
    var draft=editableDraft();draft.setTitle("Agent草稿编辑后保存");store.updateDraft(ID,draft,0L);
    assertEquals(91L,store.apply(ID,null,1L).getId());assertEquals(91L,store.apply(ID,null,0L).getId());
    assertEquals("Agent草稿编辑后保存",trips.load(91L).getTitle());runGeneration();
    verify(rag,times(1)).retrieve(any(),eq(ID));verify(provider,times(1)).complete(anyString());verify(trips,times(1)).createGenerated(any());
    assertEquals(1,mapper.valueToTree(metadata().get("agent")).path("traces").size());
  }
  @Test void agentFailureFailsTaskAndRefundsOnceWithoutFinalGeneration() {
    enableAgent(true);runGeneration();runGeneration();assertEquals("FAILED",row.get("status"));
    assertEquals("FAILED",mapper.valueToTree(metadata().get("agent")).path("status").asText());
    verify(provider,never()).complete(anyString());verify(db,times(1)).update(contains("ai_quota_used=GREATEST"),any(Object[].class));
    assertEquals(1,((List<?>)metadata().get("modelCalls")).size());
  }
  @Test void modelLedgerEnforcesLimitAndCannotBeCompletedTwice() {
    store.start(ID);int call=store.beginModelCall(ID,"AGENT",1);
    store.finishModelCall(ID,call,Map.of("status","SUCCEEDED","inputTokens",10,"outputTokens",5,"durationMs",2));
    store.finishModelCall(ID,call,Map.of("status","SUCCEEDED","inputTokens",100,"outputTokens",100));
    assertEquals(15,mapper.valueToTree(metadata().get("modelUsage")).path("totalTokens").asInt());
    assertEquals(422,assertThrows(BusinessException.class,()->store.beginModelCall(ID,"JSON_GENERATION",1)).getCode());
  }
  @Test void idempotentRecoveryDoesNotRequireAgentToRemainEnabledOrReady() {
    enableRag();runGeneration();row.put("request_hash",CommunityService.hash("GENERATE\nnull\n"+trips.encode(requirements.confirmed(requirement()))));
    var unavailable=mock(com.smarttrip.agent.TravelAgentService.class);doThrow(new BusinessException(503,"fixture-unverified")).when(unavailable).requireReady();service.configureAgent(unavailable);
    assertEquals(ID,service.submitGeneration("same-request-key",requirement(),true).get("id"));verify(unavailable,never()).requireReady();verify(provider,times(1)).complete(anyString());
  }
  @Test void explicitModelScopedRatesEstimateEveryAgentAndFinalCallWithoutPretendingInvoiceCost() {
    var pricing=new com.smarttrip.config.TravelAgentProperties();pricing.setPricingModel(provider.modelName());pricing.setPriceCurrency("USD");pricing.setInputPricePerMillion(new BigDecimal("0.10"));pricing.setOutputPricePerMillion(new BigDecimal("0.20"));store.configureAgentPricing(pricing);
    store.start(ID);int first=store.beginModelCall(ID,"AGENT",2);store.finishModelCall(ID,first,Map.of("status","SUCCEEDED","inputTokens",1000000,"outputTokens",500000));
    int second=store.beginModelCall(ID,"JSON_GENERATION",2);store.finishModelCall(ID,second,Map.of("status","SUCCEEDED","inputTokens",100000,"outputTokens",50000));
    var usage=mapper.valueToTree(metadata().get("modelUsage"));assertEquals(0,usage.path("estimatedCost").decimalValue().compareTo(new BigDecimal("0.22")));assertEquals("USD",usage.path("costCurrency").asText());assertEquals("ESTIMATED_TOKEN_RATES",usage.path("costStatus").asText());
  }
  @Test void mismatchedPricingIsUnknownRatherThanFree() {
    var pricing=new com.smarttrip.config.TravelAgentProperties();pricing.setPricingModel("other-model");pricing.setPriceCurrency("USD");pricing.setInputPricePerMillion(BigDecimal.ONE);pricing.setOutputPricePerMillion(BigDecimal.ONE);store.configureAgentPricing(pricing);
    store.start(ID);int call=store.beginModelCall(ID,"AGENT",1);store.finishModelCall(ID,call,Map.of("status","SUCCEEDED","inputTokens",10,"outputTokens",5));
    var usage=mapper.valueToTree(metadata().get("modelUsage"));assertTrue(usage.path("estimatedCost").isNull());assertEquals("UNKNOWN_MODEL_PRICING_MISMATCH",usage.path("costStatus").asText());
  }
}
