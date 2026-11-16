package com.smarttrip.mcp;

import com.smarttrip.agent.*;
import com.smarttrip.agent.TravelAgentToolService.*;
import com.smarttrip.common.*;
import com.smarttrip.config.TravelAgentProperties;
import com.smarttrip.service.AiTaskStore;
import java.util.Map;
import org.springframework.ai.mcp.annotation.*;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="smarttrip.mcp.server.enabled",havingValue="true")
public class TravelMcpTools {
  private final TravelAgentToolService tools;private final AiTaskStore store;private final TravelAgentProperties limits;
  public TravelMcpTools(TravelAgentToolService tools,AiTaskStore store,TravelAgentProperties limits){this.tools=tools;this.store=store;this.limits=limits;}
  @McpTool(name="searchAttractions",description="只读搜索真实地点，不超过5条；不提供报价或预订",annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true))
  public Map<String,Object> searchAttractions(@McpToolParam(required=true) AttractionInput input,McpSyncRequestContext context){return invoke("searchAttractions",input,context,null);}
  @McpTool(name="getWeatherForecast",description="只读查询今天至未来3天的实际天气预报，超出范围返回不可用",annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true))
  public Map<String,Object> getWeatherForecast(@McpToolParam(required=true) WeatherInput input,McpSyncRequestContext context){return invoke("getWeatherForecast",input,context,null);}
  @McpTool(name="calculateBudget",description="纯计算：整段行程的估算费用，不扣款、不保存；须明确币种和费用口径",annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true))
  public Map<String,Object> calculateBudget(@McpToolParam(required=true) BudgetInput input,McpSyncRequestContext context){return invoke("calculateBudget",input,context,null);}
  @McpTool(name="getTravelKnowledge",description="读取本人已有生成任务中的公开知识片段与适用期，不新检索、不返回私有Prompt",annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true))
  public Map<String,Object> getTravelKnowledge(@McpToolParam(required=true) KnowledgeInput input,McpSyncRequestContext context){return invoke("getTravelKnowledge",input,context,input==null?null:input.taskId());}
  private Map<String,Object> invoke(String name,Object input,McpSyncRequestContext request,String taskId){
    Object principal=request==null?null:request.transportContext().get(TravelMcpConfiguration.USER);
    if(!(principal instanceof Long user)||user<=0)throw new BusinessException(401,"MCP工具调用缺少可信身份");
    Long old=UserContext.getUserId();
    try{
      UserContext.setUserId(user);
      com.smarttrip.rag.TravelRagContext rag=null;com.smarttrip.entity.TravelRequirement requirement=null;
      if("getTravelKnowledge".equals(name)){
        if(taskId==null||!taskId.matches("[0-9a-f-]{36}"))throw new BusinessException(400,"须提供本人已有任务ID");
        rag=store.existingRagContext(taskId);requirement=store.readGenerationRequirement(taskId);
      }
      limits.validateLimits();
      return tools.invoke(name,tools.encode(input),new TravelAgentExecutionContext(taskId,user,requirement,rag,limits,new TravelAgentExecutionContext.Observer(){}));
    }finally{if(old==null)UserContext.clear();else UserContext.setUserId(old);}
  }
}
