<script setup lang="ts">
import { computed } from "vue";
import type { Task } from "./types";
const props = defineProps<{ task?: Task }>();
const agent = computed(() => props.task?.generation?.agent);
const names: Record<string,string> = { searchAttractions: "地点搜索", getWeatherForecast: "天气预报", calculateBudget: "预算计算", getTravelKnowledge: "旅行知识" };
const states: Record<string,string> = { RUNNING: "处理中", SUCCEEDED: "已完成", UNAVAILABLE: "暂不可用", TIMEOUT: "查询超时", FAILED: "未完成", DUPLICATE: "已限制重复调用" };
const kinds: Record<string,string> = { FORECAST: "天气预报", EXTERNAL_QUERY: "地点查询", ESTIMATE: "费用估算", HISTORICAL_REFERENCE: "参考资料" };
const providers: Record<string,string> = { AMAP: "高德地图", JAVA_COMPUTATION: "预算计算", SAVED_RAG_SNAPSHOT: "本次已存知识" };
function reason(code: string): string {
  const messages: Record<string,string> = { TOOL_TIMEOUT: "本次查询超时", DUPLICATE_TOOL_CALL: "已限制重复查询", INVALID_ARGUMENT: "查询参数无效或与确认条件不一致", OUTSIDE_FORECAST_WINDOW: "日期超出当前预报范围", OUTSIDE_CONFIRMED_TRAVEL_DATES: "预报日期与旅行日期不对应", CONFIRMED_BUDGET_CONDITIONS_UNKNOWN: "人数、天数或币种尚未明确", NO_SAVED_KNOWLEDGE: "本次没有可用的知识快照", TOOL_RESULT_TOO_LARGE: "返回信息超出安全长度限制", TOOL_BUSY: "查询服务繁忙", TOOL_UNAVAILABLE: "查询暂时不可用", DATA_UNAVAILABLE: "暂无可确认数据" };
  if (messages[code]) return messages[code];
  if (code.endsWith("DISABLED")) return "相关查询服务未开启";
  if (code.endsWith("QUOTA_UNVERIFIED")) return "查询服务额度尚未确认";
  if (code.endsWith("KEY_MISSING")) return "查询服务尚未配置完整";
  return "该项信息仍待核实";
}
</script>

<template>
  <section v-if="agent" class="agent-execution" aria-label="本次智能查询记录">
    <p role="status"><strong>智能资料整理</strong> · {{ agent.status === "COMPLETED" ? "已完成" : agent.status === "FAILED" ? "未完成" : "处理中" }}</p>
    <p v-if="agent.summary">{{ agent.summary }}</p>
    <details v-if="agent.traces?.length">
      <summary>查看本次工具执行记录（{{ agent.traces.length }}）</summary>
      <ol>
        <li v-for="trace in agent.traces" :key="trace.sequence">
          <strong>{{ names[trace.toolName] || "只读查询" }}</strong>
          <span>{{ states[trace.status] || "处理中" }}<template v-if="trace.status !== 'RUNNING'"> · {{ trace.durationMs }} ms</template></span>
          <small>{{ trace.sanitizedInputSummary }}</small>
          <small v-if="trace.errorCode">{{ reason(trace.errorCode) }}</small>
        </li>
      </ol>
      <ul v-if="agent.toolSources?.length" aria-label="工具实际返回的资料来源">
        <li v-for="(source,index) in agent.toolSources" :key="index">{{ providers[source.provider] || '只读来源' }} · {{ kinds[source.dataKind] || '待核实资料' }}<span v-if="source.date"> · {{ source.date }}</span></li>
      </ul>
    </details>
    <div v-if="agent.evaluation" class="agent-evaluation">
      <strong>{{ agent.evaluation.status === "WARNINGS" ? "需要留意" : "一致性检查完成" }}</strong>
      <ul v-if="agent.evaluation.warnings.length"><li v-for="warning in agent.evaluation.warnings" :key="warning">{{ warning }}</li></ul>
      <small>{{ agent.evaluation.notice }}</small>
    </div>
  </section>
</template>

<style scoped>
.agent-execution { padding: 16px 18px; margin-block: 14px; border: 1px solid #ffffffa6; border-radius: 18px; background: linear-gradient(125deg,#f5fbffd9,#e4f3ffb8); color:#193c59; box-shadow: inset 0 1px #fff9,0 5px 16px #123c5c09; }
.agent-execution p { margin: 0 0 8px; font-size:14px; line-height:1.6; }
.agent-execution summary { cursor:pointer; padding-block:6px; font-size:14px; }
.agent-execution ol,.agent-execution ul { padding-left:20px; margin:8px 0; }
.agent-execution li { padding:6px 0; line-height:1.6; font-size:13px; }
.agent-execution li span { margin-left:10px; }
.agent-execution small { display:block; font-size:12px; line-height:1.6; color:#456078; }
.agent-evaluation { border-top:1px solid #31577c20; margin-top:12px; padding-top:12px; font-size:13px; }
summary:focus-visible { outline:2px solid #1972b5; outline-offset:3px; border-radius:4px; }
</style>
