package com.smarttrip.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Objective checks only. This does not prove semantic satisfaction of arbitrary travel wishes. */
@Service
public class TravelAgentEvaluator {
  public Map<String,Object> evaluate(JsonNode result,TravelAgentExecutionContext c){
    var warnings=new ArrayList<String>();var r=c.requirement;
    if(r!=null){
      if(!new HashSet<>(strings(result.path("destinations"))).equals(new HashSet<>(r.destinations()==null?List.of():r.destinations())))warnings.add("Agent目的地表述与确认条件不一致，最终生成必须沿用确认目的地");
      if(r.days()!=null&&(!result.path("days").isIntegralNumber()||r.days()!=result.path("days").asInt()))warnings.add("Agent天数与确认需求不一致");
      if(r.travelers()!=null&&(!result.path("travelers").isIntegralNumber()||r.travelers()!=result.path("travelers").asInt()))warnings.add("Agent人数与确认需求不一致");
      for(var entry:Map.of("budgetScope",Objects.toString(r.budgetScope(),"UNKNOWN"),"currency",Objects.toString(r.currency(),"")).entrySet())
        if(!entry.getValue().equals(result.path(entry.getKey()).asText()))warnings.add("Agent预算口径或币种与确认需求不一致");
      if(!new HashSet<>(strings(result.path("mustVisit"))).equals(new HashSet<>(r.mustVisit()==null?List.of():r.mustVisit()))
          ||!new HashSet<>(strings(result.path("avoid"))).equals(new HashSet<>(r.avoid()==null?List.of():r.avoid())))warnings.add("Agent未完整保留必去或避开条件；最终逐日草稿仍需校验，特殊限制待核实");
    }
    Set<String> ids=new HashSet<>();if(c.rag!=null&&c.rag.snapshot().get("sources") instanceof List<?> sources)
      for(var source:sources)if(source instanceof Map<?,?> s)ids.add(String.valueOf(s.get("chunkId")));
    if(strings(result.path("sourceIds")).stream().anyMatch(id->!ids.contains(id)))warnings.add("Agent引用了本任务不存在的知识来源，禁止在最终草稿中使用");
    var weather=new ArrayList<Map<?,?>>();
    for(var entry:c.results())if("getWeatherForecast".equals(entry.get("tool"))&&entry.get("result") instanceof Map<?,?> output&&Boolean.TRUE.equals(output.get("available")))weather.add(output);
    for(var entry:c.results())if("calculateBudget".equals(entry.get("tool"))&&entry.get("result") instanceof Map<?,?> output){
      if(Boolean.TRUE.equals(output.get("exceedsBudget")))warnings.add("已知估算费用超过确认预算，不能以虚构价格降低总额");
      if(output.get("unknownCategories") instanceof List<?> unknown&&!unknown.isEmpty())warnings.add("部分费用未知，不能当作零费用或确认已满足预算");
    }
    for(var claim:result.path("weatherClaims")){
      LocalDate date;try{date=LocalDate.parse(claim.path("date").asText());}catch(Exception e){warnings.add("Agent天气日期无效");continue;}
      LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
      if(date.isBefore(today)||date.isAfter(today.plusDays(3))||weather.stream().noneMatch(w->claim.path("date").asText().equals(w.get("date"))&&claim.path("dayWeather").asText().equals(w.get("dayWeather"))&&claim.path("nightWeather").asText().equals(w.get("nightWeather"))))warnings.add("Agent天气未得到本次有效工具响应支持，禁止当成真实预报");
      var travel=com.smarttrip.rag.knowledge.KnowledgeValidity.travelDates(r);
      if(travel==null||date.isBefore(travel.from())||date.isAfter(travel.until()))warnings.add("天气日期与确认旅行日期不对应，不能描述为本次旅行的适用预报");
    }
    if("REALTIME".equals(result.path("dataKind").asText()))warnings.add("Agent将参考或估算误称实时；资料、费用及未知信息必须明确区分");
    if(c.traces().stream().anyMatch(t->!"SUCCEEDED".equals(t.status())))warnings.add("部分工具不可用或被限制，相关数据保持未知");
    return Map.of("status",warnings.isEmpty()?"CHECKED":"WARNINGS","warnings",warnings,
        "notice","仅核对结构化一致性和实际工具证据；不代表实时价格、营业时间、交通及所有特殊要求已核实");
  }
  private List<String> strings(JsonNode node){var out=new ArrayList<String>();if(node.isArray())for(var v:node)if(v.isTextual()&&v.asText().length()<=200)out.add(v.asText());return out;}
}
