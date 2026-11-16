package com.smarttrip.agent;

import java.util.*;

public record TravelAgentResult(String promptContext,Map<String,Object> snapshot) {}
