package com.smarttrip.agent;

public record TravelAgentToolTrace(int sequence, String toolName, String status, long durationMs,
    String sanitizedInputSummary, String sanitizedOutputSummary, String errorCode, String createdAt) {}
