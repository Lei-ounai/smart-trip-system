package com.smarttrip.agent;

import com.smarttrip.common.BusinessException;
import com.smarttrip.config.TravelAgentProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.TravelRagContext;
import java.time.Instant;
import java.util.*;

/** Server-created context. Never deserialized from tool arguments or supplied by the model. */
public final class TravelAgentExecutionContext {
  public interface Observer {
    default void phase(String phase) {}
    default void trace(TravelAgentToolTrace trace) {}
    default int beginModel(String operation) { return 0; }
    default void finishModel(int sequence, Map<String,Object> usage) {}
  }
  public final String taskId;
  public final long userId;
  public final TravelRequirement requirement;
  public final TravelRagContext rag;
  public final TravelAgentProperties limits;
  public final Observer observer;
  private final long deadline;
  private final Set<String> seen = new HashSet<>();
  private int tools, models;
  private final List<TravelAgentToolTrace> traces = new ArrayList<>();
  private final List<Map<String,Object>> results = new ArrayList<>();

  public TravelAgentExecutionContext(String taskId,long userId,TravelRequirement requirement,
      TravelRagContext rag,TravelAgentProperties limits,Observer observer) {
    this.taskId=taskId; this.userId=userId; this.requirement=requirement; this.rag=rag;
    this.limits=limits;this.observer=observer;
    this.deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(limits.getMaxExecutionSeconds());
  }
  public long remainingMillis() {
    long remaining=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
    if(remaining<=0 || Thread.currentThread().isInterrupted())throw new BusinessException(504,"Agent 执行超时，未自动重试");
    return remaining;
  }
  public int beginModel() {
    remainingMillis();
    if(++models>=limits.getMaxTotalModelCalls())throw new BusinessException(422,"Agent 已达到模型调用上限，保留最终生成额度");
    return observer.beginModel("AGENT");
  }
  public int modelCalls(){return models;}
  public synchronized int nextTool(String key) {
    remainingMillis();
    if(++tools>limits.getMaxToolCalls())throw new BusinessException(422,"Agent 已达到工具调用上限");
    return seen.add(key)?tools:-tools;
  }
  public synchronized void record(TravelAgentToolTrace trace,Map<String,Object> result) {
    traces.add(trace);results.add(Map.of("tool",trace.toolName(),"result",result));observer.trace(trace);
  }
  public List<TravelAgentToolTrace> traces(){return List.copyOf(traces);}
  public List<Map<String,Object>> results(){return List.copyOf(results);}
  public static String now(){return Instant.now().toString();}
}
