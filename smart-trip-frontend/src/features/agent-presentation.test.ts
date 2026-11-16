import { describe, it, expect } from "vitest";
import { createSSRApp } from "vue";
import { renderToString } from "vue/server-renderer";
import AgentExecution from "./AgentExecution.vue";
import AiGenerationProgress from "./AiGenerationProgress.vue";
import type { Task, TravelRequirement } from "./types";

const task = (): Task => ({ id: "agent-fixture", type: "GENERATE", status: "RUNNING", phase: "AGENT_TOOL_COMPLETED",
  generation: { requirement: {} as TravelRequirement,
    warnings: [], expiresAt: "2030-01-01T00:00:00Z", agentEnabled: true,
    phaseHistory: [{ phase: "AGENT_ANALYZING", at: "2026-11-05T01:00:00Z" }, { phase: "AGENT_TOOL_COMPLETED", at: "2026-11-05T01:00:01Z" }],
    agent: { status: "COMPLETED", summary: "已完成资料整理", traces: [{ sequence: 1, toolName: "calculateBudget", status: "SUCCEEDED", durationMs: 15, sanitizedInputSummary: "已验证参数，未保存原始数据", sanitizedOutputSummary: "available=true", errorCode: "", createdAt: "2026-11-05T01:00:00Z" }],
      evaluation: { status: "WARNINGS", warnings: ["未知费用待核实"], notice: "仅为一致性检查" } } } });

describe("Agent execution presentation", () => {
  it("keeps existing tasks without Agent visually unchanged", async () => {
    expect(await renderToString(createSSRApp(AgentExecution, { task: { id: "old", type: "GENERATE", status: "PREVIEW" } }))).not.toContain("智能资料整理");
  });
  it("shows actual tool status, duration and evaluation in expandable records", async () => {
    const html = await renderToString(createSSRApp(AgentExecution, { task: task() }));
    expect(html).toContain("<details"); expect(html).toContain("预算计算"); expect(html).toContain("15 ms"); expect(html).toContain("未知费用待核实");
  });
  it("does not project private Prompt, hidden context, raw arguments or tokens", async () => {
    const value = task();
    Object.assign(value.generation!, { _agentContext: "PRIVATE_PROMPT_SENTINEL", _ragContext: "PRIVATE_RAG_SENTINEL" });
    Object.assign(value.generation!.agent!.traces![0]!, { arguments: "RAW_ARGUMENT_SENTINEL", jwt: "JWT_SENTINEL" });
    const html = await renderToString(createSSRApp(AgentExecution, { task: value }));
    for (const hidden of ["PRIVATE_PROMPT_SENTINEL", "PRIVATE_RAG_SENTINEL", "RAW_ARGUMENT_SENTINEL", "JWT_SENTINEL"]) expect(html).not.toContain(hidden);
  });
  it("adds Agent phases only to opted-in task progress", async () => {
    const html = await renderToString(createSSRApp(AiGenerationProgress, { task: task(), submitting: false }));
    expect(html).toContain("收到工具结果"); expect(html).toContain("查看本次工具执行记录");
  });
  it("reports incomplete Agent work without success feedback", async () => {
    const value = task(); value.generation!.agent!.status = "FAILED";
    expect(await renderToString(createSSRApp(AgentExecution, { task: value }))).toContain("未完成");
  });
});
