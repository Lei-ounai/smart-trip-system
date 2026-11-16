package com.smarttrip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "smarttrip.agent")
public class TravelAgentProperties {
  private boolean enabled;
  private boolean toolCallingVerified;
  private String verifiedModel = "";
  private String verifiedBaseUrl = "";
  private int maxAgentModelTurns = 4;
  private int maxToolCalls = 5;
  private int maxTotalModelCalls = 5;
  private int maxExecutionSeconds = 90;
  private int toolTimeoutSeconds = 10;
  private int maxToolResultCharacters = 4000;
  // Optional, explicitly scoped estimate rates. Never infer prices or an exchange rate.
  private String pricingModel = "";
  private String priceCurrency = "";
  private java.math.BigDecimal inputPricePerMillion;
  private java.math.BigDecimal outputPricePerMillion;

  public String pricingStatus(String model) {
    String selected=java.util.Objects.toString(pricingModel,"");String currency=java.util.Objects.toString(priceCurrency,"");
    if(selected.isBlank()&&currency.isBlank()&&inputPricePerMillion==null&&outputPricePerMillion==null)
      return "UNKNOWN_NO_CONFIGURED_PRICING";
    if(!selected.equals(model))return "UNKNOWN_MODEL_PRICING_MISMATCH";
    if(!currency.matches("[A-Z]{3}")||inputPricePerMillion==null||outputPricePerMillion==null
        ||inputPricePerMillion.signum()<0||outputPricePerMillion.signum()<0
        ||inputPricePerMillion.scale()>12||outputPricePerMillion.scale()>12
        ||inputPricePerMillion.compareTo(new java.math.BigDecimal("1000000"))>0
        ||outputPricePerMillion.compareTo(new java.math.BigDecimal("1000000"))>0)
      return "UNKNOWN_INVALID_PRICING";
    try{java.util.Currency.getInstance(currency);}catch(Exception e){return "UNKNOWN_INVALID_PRICING";}
    return "ESTIMATED_TOKEN_RATES";
  }

  public void validateLimits() {
    if (maxAgentModelTurns < 1 || maxAgentModelTurns > 8 || maxToolCalls < 1 || maxToolCalls > 12
        || maxTotalModelCalls < 2 || maxTotalModelCalls > 9 || maxAgentModelTurns >= maxTotalModelCalls
        || maxExecutionSeconds < 1 || maxExecutionSeconds > 180 || toolTimeoutSeconds < 1
        || toolTimeoutSeconds > 15 || maxToolResultCharacters < 512 || maxToolResultCharacters > 8000)
      throw new com.smarttrip.common.BusinessException(503, "Agent 安全限额配置无效");
  }
}
