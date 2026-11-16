package com.smarttrip.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.TravelAgentProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.*;
import com.smarttrip.service.GeoService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TravelAgentToolTests {
  GeoService geo;TravelAgentToolService tools;TravelAgentProperties limits;
  @BeforeEach void setup(){geo=mock(GeoService.class);tools=new TravelAgentToolService(geo);limits=new TravelAgentProperties();}
  @AfterEach void close(){tools.close();}
  TravelAgentExecutionContext context(){return new TravelAgentExecutionContext("task",7L,null,null,limits,new TravelAgentExecutionContext.Observer(){});}
  Map<String,Object> call(String name,Object input){return tools.invoke(name,tools.encode(input),context());}
  @Test void actualAttractionMethodIsUsedAndOnlyFiveRealCandidatesReturned(){
    when(geo.poiAvailable()).thenReturn(true);when(geo.search("苏州","园林")).thenReturn(java.util.stream.IntStream.range(0,8).mapToObj(i->Map.<String,Object>of("name","真实返回园林"+i,"address","苏州","poiId","ABCDE"+i,"apiKey","must-not-copy")).toList());
    var value=call("searchAttractions",new TravelAgentToolService.AttractionInput("苏州","园林"));
    assertEquals(true,value.get("available"));assertEquals(5,((List<?>)value.get("attractions")).size());assertFalse(tools.encode(value).contains("must-not-copy"));verify(geo).search("苏州","园林");
  }
  @Test void futureWeatherIsUnavailableWithoutCallingGeo(){
    var date=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(4).toString();var value=call("getWeatherForecast",new TravelAgentToolService.WeatherInput("苏州",date));
    assertEquals("OUTSIDE_FORECAST_WINDOW",value.get("reason"));verifyNoInteractions(geo);
  }
  @Test void disabledWeatherReturnsReasonWithoutRequest(){when(geo.weatherStatus()).thenReturn("DISABLED");var value=call("getWeatherForecast",new TravelAgentToolService.WeatherInput("苏州",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()));assertEquals("WEATHER_DISABLED",value.get("reason"));verify(geo,never()).weather(any(),any());}
  @Test void realForecastIsWhitelistedAndNotInvented(){String date=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();when(geo.weatherAvailable()).thenReturn(true);when(geo.weather("苏州",LocalDate.parse(date))).thenReturn(Map.of("available",true,"date",date,"dayWeather","阴","nightWeather","多云","high","19","provider","AMAP"));var result=call("getWeatherForecast",new TravelAgentToolService.WeatherInput("苏州",date));assertEquals("阴",result.get("dayWeather"));verify(geo).weather("苏州",LocalDate.parse(date));}
  TravelAgentToolService.BudgetInput budget(){return new TravelAgentToolService.BudgetInput(7,8,"CNY","PER_PERSON",new BigDecimal("0.10"),new BigDecimal("0.20"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("1.20"),"PER_PERSON");}
  @Test void bigDecimalBudgetKeepsScopeAndExactTotal(){var result=call("calculateBudget",budget());assertEquals(new BigDecimal("9.10"),result.get("totalEstimate"));assertEquals(new BigDecimal("1.30"),result.get("perPersonEstimate"));assertEquals(new BigDecimal("8.40"),result.get("totalBudgetCap"));assertEquals(true,result.get("exceedsBudget"));assertEquals("ESTIMATE",result.get("dataKind"));}
  @Test void unknownCostsAreNotFree(){var p=budget();var result=call("calculateBudget",new TravelAgentToolService.BudgetInput(7,8,"CNY","TOTAL",null,p.accommodation(),p.food(),p.tickets(),p.other(),null,"UNKNOWN"));assertNull(result.get("totalEstimate"));assertNull(result.get("perPersonEstimate"));assertEquals(List.of("transport"),result.get("unknownCategories"));}
  @Test void budgetCannotInventUnknownConfirmedTravelers(){var r=new ObjectMapper().convertValue(Map.of("days",8,"currency","CNY","budgetScope","TOTAL"),TravelRequirement.class);var c=new TravelAgentExecutionContext("task",7L,r,null,limits,new TravelAgentExecutionContext.Observer(){});assertEquals("CONFIRMED_BUDGET_CONDITIONS_UNKNOWN",tools.invoke("calculateBudget",tools.encode(budget()),c).get("reason"));}
  @Test void currentForecastIsNotSubstitutedForADifferentConfirmedTravelDay(){var today=LocalDate.now(ZoneId.of("Asia/Shanghai"));var r=new ObjectMapper().convertValue(Map.of("days",1,"startDate",today.toString()),TravelRequirement.class);var c=new TravelAgentExecutionContext("task",7L,r,null,limits,new TravelAgentExecutionContext.Observer(){});assertEquals("OUTSIDE_CONFIRMED_TRAVEL_DATES",tools.invoke("getWeatherForecast",tools.encode(new TravelAgentToolService.WeatherInput("苏州",today.plusDays(1).toString())),c).get("reason"));verifyNoInteractions(geo);}
  @Test void invalidAndUnknownArgumentsNeverReachExternalService(){assertThrows(BusinessException.class,()->tools.invoke("searchAttractions","{\"city\":\"苏州\",\"keyword\":\"x\",\"userId\":99}",context()));assertEquals(false,call("searchAttractions",new TravelAgentToolService.AttractionInput("","x")).get("available"));assertEquals(false,call("calculateBudget",new TravelAgentToolService.BudgetInput(0,1,"CNY","TOTAL",null,null,null,null,null,null,null)).get("available"));
    for(var input:List.of(new TravelAgentToolService.AttractionInput("苏州",""),new TravelAgentToolService.AttractionInput("长".repeat(101),"园林"),new TravelAgentToolService.AttractionInput("苏州","长".repeat(101)),new TravelAgentToolService.AttractionInput("苏\n州","园林")))
      assertEquals("INVALID_ARGUMENT",call("searchAttractions",input).get("reason"));
    for(int[] pair:new int[][]{{0,1},{101,1},{1,0},{1,31}})
      assertEquals("INVALID_ARGUMENT",call("calculateBudget",new TravelAgentToolService.BudgetInput(pair[0],pair[1],"CNY","TOTAL",null,null,null,null,null,null,null)).get("reason"));
    var confirmed=new ObjectMapper().convertValue(Map.of("days",8,"travelers",7,"currency","CNY","budgetScope","TOTAL"),TravelRequirement.class);
    var ctx=new TravelAgentExecutionContext("task",7L,confirmed,null,limits,new TravelAgentExecutionContext.Observer(){});
    var wrongCurrency=new TravelAgentToolService.BudgetInput(7,8,"USD","TOTAL",null,null,null,null,null,null,null);
    assertEquals("INVALID_ARGUMENT",tools.invoke("calculateBudget",tools.encode(wrongCurrency),ctx).get("reason"));verifyNoInteractions(geo);}
  @Test void duplicateToolsDoNotRepeatProviderRequests(){when(geo.poiAvailable()).thenReturn(true);when(geo.search(anyString(),anyString())).thenReturn(List.of());var c=context();String input=tools.encode(new TravelAgentToolService.AttractionInput("苏州","园林"));tools.invoke("searchAttractions",input,c);var result=tools.invoke("searchAttractions",input,c);assertEquals("DUPLICATE_TOOL_CALL",result.get("reason"));verify(geo,times(1)).search(anyString(),anyString());assertEquals(2,c.traces().size());assertFalse(tools.encode(c.traces()).contains("苏州"));}
  @Test void resultsRemainValidBoundedJson(){when(geo.poiAvailable()).thenReturn(true);when(geo.search(anyString(),anyString())).thenReturn(java.util.stream.IntStream.range(0,5).mapToObj(i->Map.<String,Object>of("name","名".repeat(250),"address","址".repeat(250),"poiId","ID".repeat(150),"location","0".repeat(250),"adcode","1".repeat(250),"provider","AMAP")).toList());limits.setMaxToolResultCharacters(512);var result=call("searchAttractions",new TravelAgentToolService.AttractionInput("苏州","园林"));assertTrue(tools.encode(result).length()<=512);assertEquals("TOOL_RESULT_TOO_LARGE",result.get("reason"));}
  @Test void toolTimeoutIsBoundedAndExceptionIsIsolated()throws Exception{
    limits.setToolTimeoutSeconds(1);when(geo.poiAvailable()).thenReturn(true);when(geo.search(anyString(),anyString())).thenAnswer(i->{Thread.sleep(3000);return List.of();});long start=System.nanoTime();var result=call("searchAttractions",new TravelAgentToolService.AttractionInput("苏州","园林"));assertEquals("TOOL_TIMEOUT",result.get("reason"));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2000);
    when(geo.search(anyString(),anyString())).thenThrow(new IllegalStateException("private-provider-detail"));assertEquals("TOOL_UNAVAILABLE",call("searchAttractions",new TravelAgentToolService.AttractionInput("苏州","寺庙")).get("reason"));
  }
  @Test void knowledgeUsesSavedSourcesAndRejectsOtherTask(){var source=Map.of("chunkId","k1","sourceName","公共资料","sourceUrl","https://example.org/info","content","仅来自已有快照");String raw=RagContextBuilder.RULES+"\n"+tools.encode(Map.of("sources",List.of(source)));
    var rag=new TravelRagContext(raw,Map.of("sources",List.of(Map.of("chunkId","k1"))));var c=new TravelAgentExecutionContext("task",7L,null,rag,limits,new TravelAgentExecutionContext.Observer(){});
    assertTrue(tools.encode(tools.invoke("getTravelKnowledge","{}",c)).contains("仅来自已有快照"));assertEquals("INVALID_ARGUMENT",tools.invoke("getTravelKnowledge","{\"taskId\":\"other\"}",context()).get("reason"));verifyNoInteractions(geo);
  }
  @Test void annualKnowledgeWithoutDatesIsExcluded(){var source=Map.of("chunkId","k1","content","2026年度限定资料","validFrom","2026-09-15","validUntil","2026-11-15");var rag=new TravelRagContext(RagContextBuilder.RULES+"\n"+tools.encode(Map.of("sources",List.of(source))),Map.of("sources",List.of(Map.of("chunkId","k1"))));var c=new TravelAgentExecutionContext("task",7L,null,rag,limits,new TravelAgentExecutionContext.Observer(){});assertEquals(false,tools.invoke("getTravelKnowledge","{}",c).get("available"));}

  @Test void fractionalHeadcountsCannotBeSilentlyRounded() {
    assertEquals(400,assertThrows(BusinessException.class,()->tools.invoke("calculateBudget",
        "{\"travelers\":7.8,\"days\":8.4,\"currency\":\"CNY\",\"costScope\":\"TOTAL\"}",context())).getCode());
    verifyNoInteractions(geo);
  }

}
