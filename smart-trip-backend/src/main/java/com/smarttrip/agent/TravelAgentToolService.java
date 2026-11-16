package com.smarttrip.agent;

import com.fasterxml.jackson.databind.*;
import com.smarttrip.common.*;
import com.smarttrip.config.TravelAgentProperties;
import com.smarttrip.rag.RagContextBuilder;
import com.smarttrip.rag.knowledge.KnowledgeValidity;
import com.smarttrip.service.GeoService;
import jakarta.annotation.PreDestroy;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/** The only tool business implementation, shared by manual Spring AI callbacks and MCP adapters. */
@Service
public class TravelAgentToolService {
  public record AttractionInput(String city,String keyword) {}
  public record WeatherInput(String city,String date) {}
  public record KnowledgeInput(@org.springframework.ai.tool.annotation.ToolParam(required=false)
      @org.springframework.ai.mcp.annotation.McpToolParam(required=false) String taskId) {}
  /** Each category is an estimate for the entire trip, either all travelers or one traveler. */
  public record BudgetInput(
      @org.springframework.ai.tool.annotation.ToolParam(description="实际人数，必须与已确认需求完全相同，1至100的整数") Integer travelers,
      @org.springframework.ai.tool.annotation.ToolParam(description="整段行程天数，必须与已确认需求完全相同，1至30的整数") Integer days,
      @org.springframework.ai.tool.annotation.ToolParam(description="已确认的ISO币种代码，例如人民币CNY；不得使用人民币汉字或擅自换汇") String currency,
      @org.springframework.ai.tool.annotation.ToolParam(description="只能为TOTAL或PER_PERSON。TOTAL表示全团合计，PER_PERSON表示单人合计。用户提供的全体费用须为TOTAL")
      @org.springframework.ai.mcp.annotation.McpToolParam(description="只能为TOTAL或PER_PERSON，分别表示全团合计或单人合计") String costScope,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) @org.springframework.ai.mcp.annotation.McpToolParam(required=false) BigDecimal transport,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) @org.springframework.ai.mcp.annotation.McpToolParam(required=false) BigDecimal accommodation,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) @org.springframework.ai.mcp.annotation.McpToolParam(required=false) BigDecimal food,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) @org.springframework.ai.mcp.annotation.McpToolParam(required=false) BigDecimal tickets,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) @org.springframework.ai.mcp.annotation.McpToolParam(required=false) BigDecimal other,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) @org.springframework.ai.mcp.annotation.McpToolParam(required=false) BigDecimal budgetMax,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) @org.springframework.ai.mcp.annotation.McpToolParam(required=false) String budgetScope) {}
  public static final Map<String,Class<?>> TYPES=Map.of("searchAttractions",AttractionInput.class,
      "getWeatherForecast",WeatherInput.class,"calculateBudget",BudgetInput.class,"getTravelKnowledge",KnowledgeInput.class);
  private final GeoService geo;
  private final ObjectMapper json=new ObjectMapper().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"travel-readonly-tool");t.setDaemon(true);return t;});
  public TravelAgentToolService(GeoService geo){this.geo=geo;}
  @PreDestroy public void close(){workers.shutdownNow();}

  public Map<String,Object> invoke(String name,String arguments,TravelAgentExecutionContext context) {
    if(context==null || context.userId<=0)throw new BusinessException(401,"工具调用缺少可信身份");
    if(!TYPES.containsKey(name))throw new BusinessException(403,"工具未注册");
    Object input;
    try {if(arguments==null || arguments.length()>6000)throw new IllegalArgumentException();
      input=json.readValue(arguments,TYPES.get(name));if(input==null)throw new IllegalArgumentException();
    }catch(Exception e){throw new BusinessException(400,"工具参数无效");}
    int sequence;
    try {sequence=context.nextTool(name+json.writeValueAsString(input));}
    catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BusinessException(400,"工具参数无效");}
    long started=System.nanoTime();String status="FAILED",error="";
    Map<String,Object> result;
    context.observer.phase("AGENT_TOOL_CALLING");
    context.observer.trace(new TravelAgentToolTrace(Math.abs(sequence),name,"RUNNING",0,
        "已验证结构化参数；未保存原始参数","等待实际工具结果","",TravelAgentExecutionContext.now()));
    if(sequence<0){result=unavailable("DUPLICATE_TOOL_CALL");status="DUPLICATE";error="DUPLICATE_TOOL_CALL";}
    else {
      Future<Map<String,Object>> future=null;
      try {
        future=workers.submit(()->perform(input,context));
        result=future.get(Math.min(context.remainingMillis(),context.limits.getToolTimeoutSeconds()*1000L),TimeUnit.MILLISECONDS);
        status=Boolean.FALSE.equals(result.get("available"))?"UNAVAILABLE":"SUCCEEDED";
      }catch(TimeoutException e){if(future!=null)future.cancel(true);result=unavailable("TOOL_TIMEOUT");status="TIMEOUT";error="TOOL_TIMEOUT";}
      catch(InterruptedException e){if(future!=null)future.cancel(true);Thread.currentThread().interrupt();throw new BusinessException(504,"工具查询已中断");}
      catch(ExecutionException e){
        error=e.getCause() instanceof BusinessException b&&b.getCode()==400?"INVALID_ARGUMENT":"TOOL_UNAVAILABLE";
        result=unavailable(error);
        if(input instanceof BudgetInput && e.getCause() instanceof BusinessException b && b.getCode()==400){
          String guidance=switch(b.getMessage()){
            case "人数或天数无效"->"travelers须为1至100整数，days须为1至30整数，且与确认需求相同";
            case "须明确 ISO 币种"->"currency须为已确认的ISO代码；人民币为CNY，不能使用中文币种名";
            case "须明确费用口径"->"costScope只能为TOTAL（全团）或PER_PERSON（单人），不能填写其他值";
            case "计算条件与已确认需求不一致"->"travelers、days、currency必须逐项沿用已确认条件，不能自行替换";
            case "预算上限口径不明确"->"budgetScope只能为TOTAL或PER_PERSON，须沿用已确认预算口径";
            case "金额无效"->"已知费用须为非负数，最多两位小数，未知费用用null";
            default->"请核对工具Schema与已确认条件，不要重复猜测参数";
          };
          result=Map.of("available",false,"reason",error,"guidance",guidance);
        }
      }
      catch(RejectedExecutionException e){error="TOOL_BUSY";result=unavailable(error);}
    }
    result=bounded(result,context.limits.getMaxToolResultCharacters());
    if(Boolean.FALSE.equals(result.get("available"))){
      if("SUCCEEDED".equals(status))status="UNAVAILABLE";
      String reason=Objects.toString(result.get("reason"),"DATA_UNAVAILABLE");
      if(error.isEmpty())error=reason.matches("[A-Z_]{1,80}")?reason:"DATA_UNAVAILABLE";
    }
    var trace=new TravelAgentToolTrace(Math.abs(sequence),name,status,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),
        "已验证结构化参数；未保存原始参数","available="+result.getOrDefault("available",false),error,TravelAgentExecutionContext.now());
    context.record(trace,result);context.observer.phase("AGENT_TOOL_COMPLETED");return result;
  }
  private Map<String,Object> perform(Object input,TravelAgentExecutionContext c) {
    if(input instanceof AttractionInput p){text(p.city(),100);text(p.keyword(),100);
      if(!geo.poiAvailable())return unavailable("POI_"+geo.poiStatus());
      var rows=geo.search(p.city(),p.keyword()).stream().limit(5).map(row->{var clean=new LinkedHashMap<String,Object>();
        for(String key:List.of("poiId","name","address","location","adcode","coordinateSystem","provider"))
          if(row.get(key)!=null)clean.put(key,clip(String.valueOf(row.get(key)),200));return clean;}).toList();
      return Map.of("available",true,"dataKind","EXTERNAL_QUERY","provider","AMAP","city",p.city(),"attractions",rows,"verifiedPrices",false,"retrievedAt",Instant.now().toString());
    }
    if(input instanceof WeatherInput p){text(p.city(),100);LocalDate date;
      try {if(p.date()==null||!p.date().matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();date=LocalDate.parse(p.date());}
      catch(Exception e){throw new BusinessException(400,"天气日期须为 ISO 日期");}
      LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
      if(date.isBefore(today)||date.isAfter(today.plusDays(3)))return Map.of("available",false,"reason","OUTSIDE_FORECAST_WINDOW","date",p.date());
      var travel=KnowledgeValidity.travelDates(c.requirement);
      if(travel!=null&&(date.isBefore(travel.from())||date.isAfter(travel.until())))return unavailable("OUTSIDE_CONFIRMED_TRAVEL_DATES");
      if(!geo.weatherAvailable())return unavailable("WEATHER_"+geo.weatherStatus());
      var raw=geo.weather(p.city(),date);var clean=new LinkedHashMap<String,Object>();
      for(String key:List.of("available","provider","city","date","dayWeather","nightWeather","high","low","issuedAt","retrievedAt"))
        if(raw.get(key)!=null)clean.put(key,raw.get(key));
      clean.put("dataKind","FORECAST");return clean;
    }
    if(input instanceof BudgetInput p)return budget(p,c);
    if(input instanceof KnowledgeInput p){
      if(p.taskId()!=null&&!p.taskId().equals(c.taskId))throw new BusinessException(400,"仅允许读取当前用户指定任务的知识快照");
      if(c.rag==null||c.rag.promptContext().isBlank()||!c.rag.promptContext().startsWith(RagContextBuilder.RULES+"\n"))return unavailable("NO_SAVED_KNOWLEDGE");
      try {
        var root=json.readTree(c.rag.promptContext().substring(RagContextBuilder.RULES.length()+1));
        Set<String> allowed=new HashSet<>();Object sources=c.rag.snapshot().get("sources");
        if(sources instanceof List<?> list)for(var source:list)if(source instanceof Map<?,?> m)allowed.add(String.valueOf(m.get("chunkId")));
        var items=new ArrayList<Map<String,Object>>();
        for(var source:root.path("sources")){
          if(!allowed.contains(source.path("chunkId").asText()))continue;
          var valid=KnowledgeValidity.window(source.hasNonNull("validFrom")?source.get("validFrom").asText():null,source.hasNonNull("validUntil")?source.get("validUntil").asText():null);
          var dates=KnowledgeValidity.travelDates(c.requirement);
          if(valid!=null&&(dates==null||dates.until().isBefore(valid.from())||dates.from().isAfter(valid.until())))continue;
          var item=new LinkedHashMap<String,Object>();
          for(String key:List.of("ref","documentId","chunkId","title","sourceName","sourceUrl","sourceUpdatedAt","validFrom","validUntil","applicability","applicableFrom","applicableUntil","applicabilityNote","content"))
            if(source.hasNonNull(key))item.put(key,clip(source.get(key).asText(),key.equals("content")?550:200));
          items.add(item);if(items.size()>=5)break;
        }
        return Map.of("available",!items.isEmpty(),"dataKind","HISTORICAL_REFERENCE","sources",items,"reason",items.isEmpty()?"NO_APPLICABLE_SOURCE":"SAVED_SNAPSHOT");
      }catch(Exception e){return unavailable("SAVED_KNOWLEDGE_INVALID");}
    }
    throw new BusinessException(400,"工具参数类型无效");
  }
  private Map<String,Object> budget(BudgetInput p,TravelAgentExecutionContext c){
    if(p.travelers()==null||p.travelers()<1||p.travelers()>100||p.days()==null||p.days()<1||p.days()>30)throw new BusinessException(400,"人数或天数无效");
    try{if(p.currency()==null||!p.currency().matches("[A-Z]{3}"))throw new IllegalArgumentException();Currency.getInstance(p.currency());}
    catch(Exception e){throw new BusinessException(400,"须明确 ISO 币种");}
    if(!Set.of("TOTAL","PER_PERSON").contains(Objects.toString(p.costScope(),"")))throw new BusinessException(400,"须明确费用口径");
    var r=c.requirement;
    if(r!=null&&(r.travelers()==null||r.days()==null||r.currency()==null||r.currency().isBlank()))return unavailable("CONFIRMED_BUDGET_CONDITIONS_UNKNOWN");
    if(r!=null&&(r.travelers()!=null&&!r.travelers().equals(p.travelers())||r.days()!=null&&!r.days().equals(p.days())||r.currency()!=null&&!r.currency().isBlank()&&!r.currency().equals(p.currency())))throw new BusinessException(400,"计算条件与已确认需求不一致");
    var amounts=new LinkedHashMap<String,BigDecimal>();amounts.put("transport",p.transport());amounts.put("accommodation",p.accommodation());amounts.put("food",p.food());amounts.put("tickets",p.tickets());amounts.put("other",p.other());
    BigDecimal subtotal=BigDecimal.ZERO;var unknown=new ArrayList<String>();
    for(var e:amounts.entrySet()){if(e.getValue()==null){unknown.add(e.getKey());continue;}money(e.getValue());subtotal=subtotal.add(e.getValue());}
    if("PER_PERSON".equals(p.costScope()))subtotal=subtotal.multiply(BigDecimal.valueOf(p.travelers()));
    BigDecimal cap=r!=null?(r.budgetMax()!=null?r.budgetMax():r.budget()):p.budgetMax();String scope=r!=null?r.budgetScope():p.budgetScope();
    if(cap!=null){money(cap);if(!Set.of("TOTAL","PER_PERSON").contains(Objects.toString(scope,"")))throw new BusinessException(400,"预算上限口径不明确");if("PER_PERSON".equals(scope))cap=cap.multiply(BigDecimal.valueOf(p.travelers()));}
    var out=new LinkedHashMap<String,Object>();out.put("available",true);out.put("dataKind","ESTIMATE");out.put("currency",p.currency());out.put("costScope",p.costScope());out.put("categories",amounts);
    out.put("knownTotal",subtotal);out.put("totalEstimate",unknown.isEmpty()?subtotal:null);out.put("perPersonEstimate",unknown.isEmpty()?subtotal.divide(BigDecimal.valueOf(p.travelers()),2,RoundingMode.HALF_UP):null);out.put("unknownCategories",unknown);out.put("totalBudgetCap",cap);out.put("exceedsBudget",cap==null?null:subtotal.compareTo(cap)>0);out.put("assumptions",List.of("各类费用是整个行程的估算，非实时报价","空费用保持未知，不视为免费","人均展示四舍五入至两位，总额使用原精确值"));return out;
  }
  private void money(BigDecimal n){if(n.signum()<0||n.scale()>2||n.precision()>12||n.compareTo(new BigDecimal("1000000000"))>0)throw new BusinessException(400,"金额无效");}
  private void text(String s,int limit){if(s==null||s.isBlank()||s.length()>limit||s.chars().anyMatch(Character::isISOControl))throw new BusinessException(400,"文字参数无效");}
  public Map<String,Object> bounded(Map<String,Object> result,int limit){
    try{if(json.writeValueAsString(result).length()<=limit)return result;
      return Map.of("available",false,"reason","TOOL_RESULT_TOO_LARGE","truncated",true);
    }catch(Exception e){return unavailable("INVALID_TOOL_RESULT");}
  }
  public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new BusinessException(400,"工具数据无效");}}
  public static Map<String,Object> unavailable(String reason){return Map.of("available",false,"reason",reason);}
  private String clip(String text,int max){return text.length()<=max?text:text.substring(0,max);}
}
