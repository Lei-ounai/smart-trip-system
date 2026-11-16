# SmartTrip V2 最终总验收报告

本文记录 2026-11-05 的 V2 验收。发布轮次的复验结果记录在发布 PR 中。

## 1. 结论

**核心功能通过，但存在明确遗留项。**

真实 qwen3.8-flash Tool Calling、真实 Java 预算/知识工具、RAG 快照、Evaluator、最终 JSON、浏览器编辑保存及工作台重读均已通过。独立官方 MCP SDK 的发现/调用、真实 JWT/Redis 安全检查也已通过。

**不能宣布全部验收通过：用户确认没有配置景点/天气服务 Key。** 两项真实外部查询的正向业务没有验收；只验收了不可用、无越权调用及日期越界等安全行为，没有用 Mock 结果冒充真实景点或天气。

本次历史验收共执行 **12 次**真实模型请求。按当日未折扣费率估算消耗 **$0.005357572**。账单金额和免费额度余额未测量。

## 2. 环境与隔离边界

| 环节 | 实际结果 |
| --- | --- |
| 分支 / HEAD | `feat/agent-mcp-v2` / `d46c579d11349f307d1e01fb9e915afe44e76a88` |
| main | `0d251897e68ffb8cfdb27a463b69bd47d48cb6b8`，未修改、未合并 |
| Java / 框架 | JDK 21.0.8 运行；编译目标 Java 17（major 61）；Boot 4.0.8、Spring AI 2.0.1 保持原版本 |
| MySQL | 本机测试库，写入仅涉及合成测试账号、任务和路书 |
| Redis | 独立临时容器，仅监听回环地址，与现有登录和业务状态隔离 |
| ES | 8.19.23，green；正式别名 `smarttrip-travel-v1`，8 文档 / 16 切片 |
| Ollama | 已安装 BGE-M3，实际 Embedding 1024 维，与 ES 的 bge-m3/v1 映射一致 |
| 应用验收 | 本轮源码构建的后端可执行 JAR 与 Vue 应用 |

真实检索和 Redis 验收使用本机 Docker 服务。验收期间保留原有容器配置、网络和数据卷。

## 3. 最终架构与业务调用链

`Vue Generate` → 现有登录/JWT → `TravelRequirementService.review/confirmed` → `AiTaskController` → `AiTaskService` → 用户锁/事务配额 → RAG 检索并持久化 → `TravelAgentService` → Spring AI ChatClient / ToolCallingManager → Java 只读工具 → Evaluator → Agent 快照 → 原 `AiProvider.complete` → 解析/标准化/业务校验 → PREVIEW → 编辑 → APPLY → Workbench。

独立 MCP SDK → Streamable HTTP `/api/mcp` → 现有 JWT、tokenVersion、Redis 限流、会话归属 → `TravelMcpTools` → 同一个 `TravelAgentToolService`。MCP 不调用付费 ChatClient，不创建路书。

| 环节 | 输入 → 输出 / 责任 |
| --- | --- |
| 需求确认 | 原表单及自然语言 → 用户确认的 TravelRequirement；硬性条件保留 |
| RAG | 确认需求 → BGE-M3 / BM25 / kNN / RRF → 有权限与适用期的片段、来源、私有上下文 |
| AiTaskStore | RAG 结果 → 先持久化快照，再允许 Agent 请求；复用同一任务来源 |
| Agent | 确认需求＋已有快照 → 自主 Tool Call → Java 方法结果 → 摘要及客观一致性评估 |
| 最终生成 | 原需求＋已存 RAG＋已存 Agent 数据 → 原 Provider 的结构化路书 JSON |
| 草稿与正式保存 | 校验后的可编辑草稿 → 乐观版本校验 / 用户确认 → 原工作台数据，不提前创建正式路书 |

任务状态继续使用 `QUEUED → RUNNING → PREVIEW → APPLIED`；失败为 `FAILED`。没有新增并行任务管理系统，没有改后端数据格式或前端表单字段。

## 4. 真实 Function Calling 与完整闭环证据

完整业务任务使用隔离测试账号和路书。验收后已清理本轮创建的测试记录。

- 需求：苏州出发、稻城亚丁、8 天、7 人、人均 CNY 3000～4500、日期待定；总预算上限 ¥31,500。
- 模型自主调用 `calculateBudget`，真实 Java 执行并回传，Agent 正常结束且 Evaluator 检查通过。
- 此完整业务任务仅 **2 次 Agent 请求＋1 次最终 JSON 请求**，未超过单任务 5 次上限。
- 最终供应商请求 #10：
  - Prompt 含数据库已保存的 RAG 上下文：**true**。
  - Prompt 含数据库已保存的 Agent 上下文：**true**。
  - RAG 已先于 Agent 请求写入：**true**。
  - 任务快照与发送内容的指纹一致。
- 最终模型返回完整 8 天计划，finish_reason=stop，没有截断；进入 PREVIEW。编辑活动名称、开始时间到 `08:55`、费用到 **¥20.12** 后保存。
- 工作台重读：39 个活动，已知活动合计及保存的 totalBudget 均为 **¥22470.12**。
- 草稿旧版本返回 409；重复 APPLY 返回相同路书 ID；确认前未创建正式路书，成功后只创建一份；最终状态 APPLIED，生成配额使用 1 次。
- 刷新、恢复读取、草稿编辑、重复确认、工作台读取均未再次请求模型或重检索。

另一次真实多工具协议验证使用该本人任务的既存快照：模型在同一轮自主返回 **getTravelKnowledge＋calculateBudget** 两个调用，Spring AI 实际执行两个 Java 方法并回传；第二轮摘要正常结束。预算精确合计 **¥22,800**、未超上限；2 个来源标识与原快照一致，Evaluator=CHECKED、warnings=[]。只读取既存快照，没有发起新检索或保存路书。

## 5. 四个工具的验收结果

| 工具 | 正向实际结果 | 边界与异常 |
| --- | --- | --- |
| searchAttractions | **待联调**：无 Key，GeoService 外部查询执行次数 0；真实模型曾发出该 Tool Call，Java 安全返回 POI_DISABLED | 空/超长/控制字符文本、未知字段、本机外部异常夹具、返回最多 5 条、超时及重复调用已测；未知城市的真实供应商语义响应仍待 Key |
| getWeatherForecast | **待联调**：当前日期安全返回 WEATHER_DISABLED，未伪造天气 | ISO 日期、今日至未来 3 天边界、旅行日期冲突、超范围不发外部请求、字段白名单及不可用状态已测 |
| calculateBudget | 真实模型与 MCP 均实际执行；¥22,800 总额及 BigDecimal 小数结果通过 | 人数/天数上下界、小数整数拒绝、币种冲突、人均/全团、未知费用不当免费、超预算已测 |
| getTravelKnowledge | 真实模型及 MCP 实际读取当前本人任务的 2 个已存来源，无重新检索 | 他人任务拒绝、来源一致性、年度适用期、无日期排除年度资料及私有字段隔离通过 |

没有 Tool Call 不应一概解释为错误：不需要额外工具时可以只整理已有资料，但**不会计为工具调用成功证据**，不能虚构执行记录；对应无调用/非法摘要测试均有覆盖。

## 6. 独立官方 MCP SDK 与安全

官方 Java MCP SDK 独立进程实际连接本机 Streamable HTTP，协议协商 **2025-11-25**；发现且仅发现四个只读工具，名称、描述、Schema 与只读声明可读取。

| 检查 | 实际结果 |
| --- | --- |
| 预算 / 本人知识调用 | 正常真实执行，来源与保存的快照一致 |
| 景点 / 天气调用 | 明确不可用，不伪造正常结果 |
| 他人知识 | 官方客户端收到工具错误，未得到片段正文 |
| 未登录 / 无效 / 过期 JWT | 均 401 |
| tokenVersion 变化 | 旧令牌 401；仅改变隔离验收账号，未修改密钥 |
| 他人会话 ID | 403，真实账号与真实 Redis/数据库 |
| 限流 | 30 次/分钟；累计第 31 次返回 429。测试循环前已有 1 次会话请求 |
| 默认关闭 | 最终 JAR 实际启动关闭模式，/api/mcp 返回 404 |
| 异常 / 网络 / Origin / 失效会话 | 现有协议与故障夹具回归通过；生产鉴权没有放宽 |

未在公网开放 MCP。没有 JWT 查询参数、未输出 JWT/API Key/密码，也没有把私有 RAG/Agent Prompt 或向量返回给普通客户端。

## 7. 安全、失败、恢复及关闭模式

- 新增真实 Redis 丢锁复现：旧实现允许同用户第二任务调用模型并扣额；修复后由用户行事务锁及 RUNNING 检查阻止重复入场，旧 worker 不能删除替代令牌。
- 真实浏览器失败任务在 4 次 Agent 请求后按上限停止，未执行最终 JSON、未创建路书；生成配额从 1 退回 0。重复查询/相同请求号不重复退款或生成。
- **配额返还不是供应商 Token 费用退款**：失败前真实模型调用仍计入本轮 12 次费用估算。
- 最大轮次、工具数、总请求数、重复调用、超时、中断、非法参数、过长结果、中途失败、版本冲突、幂等与恢复由确定性回归覆盖。
- 不支持 Tool Calling 的故障使用本机真实 HTTP 400 固定响应，确认失败、无工具执行、只请求一次；没有把当前实际支持工具的供应商人为改成不支持。
- Agent 关闭：真实 BGE/ES＋HTTP＋固定 Provider 的原生成、编辑、保存、工作台闭环通过，不发生 Agent 请求、不产生工具记录。
- AI/Agent 关闭的隔离应用实测：RAG 真实预览 SUCCESS，新的生成提交 503，已保存任务及路书仍能恢复。最终 JAR 另做启动、默认关闭端点及匿名鉴权冒烟检查；这些验证均未发起付费模型请求。

## 8. 前端实际浏览器记录

Chrome 桌面 1600×1000，真实登录与原表单提交；工具记录可展开、Evaluator 可见、SSE 可用、完整草稿可编辑、保存后进入原工作台。快速日期切换最终选择正确，减少动效设置与已保存路由恢复通过，pageerror 记录为空。

发现并修复阶段历史提前出现 MODEL 的问题：现在 Agent 完成后才进入最终生成；新增真实 HTTP＋固定 Provider 回归明确断言该顺序，最终全量回归通过。没有为这个显示修复额外重开付费生成。

本机截图仅用于复核草稿、工具记录和保存后的工作台。仓库保留本文所述操作及断言。

没有修改导航、原表单或工作台布局；截图是功能留证，不代替全量移动端或视觉设计验收。

## 9. 自动化与构建统计

| 检查 | 结果 |
| --- | --- |
| 最终后端完整回归 | **453 tests，0 failure，0 error，0 skipped** |
| 追加本机协议/边界专项 | **19/19**；含 1 项新协议拒绝用例。按各 suite 最新 XML 去重，共 **454 项通过**，不重复累计重跑次数 |
| 原 88 项环境门控 | 本轮全部启用；参数化用例展开使实际数量增加，不把跳过项算通过 |
| 前端 | **112/112**；TypeScript 类型检查与生产构建通过 |
| Maven | 最新可执行 JAR package/repackage 成功；不是普通 JAR 冒报 |
| 最新 JAR 启动 | 实际启动成功；关闭 MCP 返回 404，匿名 RAG 返回 401 |
| Git / 敏感数据 | diff --check 通过；本轮源码中未发现实际配置的秘密值；运行数据和构建产物未进入提交 |

部分关键 suite（每列 failure/error/skipped 均为 0）：

| Suite | 用例 | failure / error / skipped |
| --- | --- | --- |
| agent.TravelAgentOrchestratorTests | 19 | 0 / 0 / 0 |
| agent.TravelAgentProviderTransportTests | 4 | 0 / 0 / 0 |
| agent.TravelAgentToolTests | 15 | 0 / 0 / 0 |
| AiGenerationClosureIntegrationTests | 9 | 0 / 0 / 0 |
| AiRagHttpClosureIntegrationTests | 4 | 0 / 0 / 0 |
| AiWorkflowIntegrationTests | 14 | 0 / 0 / 0 |
| mcp.TravelMcpProtocolTests | 7 | 0 / 0 / 0 |
| rag.RagHybridLiveIntegrationTests | 1 | 0 / 0 / 0 |
| rag.RagKnowledgeMysqlTests | 4 | 0 / 0 / 0 |

26 条评测标注保持原样。真实 BGE-M3 和随机隔离 ES 索引的结果如下。

| 策略 | 查询数 | Recall@5 | Precision@5 | MRR@10 | 平均耗时 | 应空正确 | 异常 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BM25 | 26 | 0.863492 | 0.315385 | 0.904762 | 29.31 ms | 5/5 | 0 |
| VECTOR | 26 | 0.863492 | 0.315385 | 0.904762 | 112.19 ms | 5/5 | 0 |
| RRF 混合 | 26 | 0.863492 | 0.315385 | 0.904762 | 115.58 ms | 5/5 | 0 |

本次小语料中三个策略的质量指标相同，**不宣称混合检索优于 BM25**。Rerank 保持关闭，不把未执行的重排计为通过。额外 ACL/年度适用期负向数据只进入独立随机索引，测试结束仅清理这些测试索引。

可执行 JAR 通过启动检查，入口为 Spring Boot JarLauncher，应用入口为 com.smarttrip.SmartTripBackendApplication。

## 10. 模型用量与费用

真实模型为原配置的 qwen3.8-flash，北京区 OpenAI-compatible 地址。参考 [阿里云当日官方费率](https://www.alibabacloud.com/help/en/model-studio/qwen3-8-flash)：输入 $0.113 / 百万 Token，输出 $0.382 / 百万 Token。本表按未折扣费率计算，未推断缓存折扣或剩余免费额度。

| 请求 | 类型 | 模型返回工具 / 内容 | 输入 / 输出 Token | finish_reason | 估算 USD |
| --- | --- | --- | --- | --- | --- |
| 1 | AGENT | searchAttractions | 1160 / 40 | tool_calls | $0.000146360 |
| 2 | AGENT | getTravelKnowledge | 1229 / 14 | tool_calls | $0.000144225 |
| 3 | AGENT | 摘要/最终 JSON | 1275 / 208 | stop | $0.000223531 |
| 4 | AGENT | calculateBudget | 1917 / 140 | tool_calls | $0.000270101 |
| 5 | AGENT | calculateBudget | 2085 / 143 | tool_calls | $0.000290231 |
| 6 | AGENT | getTravelKnowledge | 2256 / 14 | tool_calls | $0.000260276 |
| 7 | AGENT | searchAttractions | 2812 / 44 | tool_calls | $0.000334564 |
| 8 | AGENT | calculateBudget | 2082 / 170 | tool_calls | $0.000300206 |
| 9 | AGENT | 摘要/最终 JSON | 2420 / 342 | stop | $0.000404104 |
| 10 | JSON_GENERATION | 摘要/最终 JSON | 2550 / 5263 | stop | $0.002298616 |
| 11 | AGENT | getTravelKnowledge、calculateBudget | 2105 / 185 | tool_calls | $0.000308535 |
| 12 | AGENT | 摘要/最终 JSON | 2209 / 333 | stop | $0.000376823 |

合计：输入 **24100**、输出 **6896**、总计 **30996 Token**；usage 缺失 **0 次**；估算 **$0.005357572**。未超过最终 12 次 / $1.40 授权，未增加生产单任务 5 次上限，没有隐式重试。

请求 1～3 为失败的景点协议探测；4～7 为已退款的浏览器失败任务；8～10 为成功的完整业务；11～12 为成功的真实并行多工具验证。供应商 HTTP 成功不等于业务成功，失败调用没有被当作草稿验收成功。

## 11. 本轮修复文件与原因

下表路径均相对于项目根目录；另新增本报告。未改 RAG 检索核心、前端页面、依赖版本或用户配置。

| 文件 | 修改必要性 |
| --- | --- |
| `smart-trip-backend/src/main/java/com/smarttrip/service/AiTaskStore.java` | 新增用户行事务入场防线，Redis 丢锁时不允许第二个 RUNNING 任务重复执行/扣额；修正无 RAG 的 Agent 初始阶段。 |
| `smart-trip-backend/src/main/java/com/smarttrip/service/AiTaskService.java` | Agent 开启时取消 RAG 后过早写入 MODEL；只有 Agent 完成才进入最终 JSON 生成阶段。 |
| `smart-trip-backend/src/main/java/com/smarttrip/agent/TravelAgentOrchestrator.java` | 明确参数/摘要契约；仅兼容完整 JSON 代码块/BOM；已有最后一轮不再广告工具，专门收尾。未增加轮次、请求数或修复调用。 |
| `smart-trip-backend/src/main/java/com/smarttrip/agent/TravelAgentToolService.java` | 拒绝小数人数/天数被 Jackson 截断；补充预算 Schema 的精确含义及白名单纠错指导，不返回原参数。 |
| `smart-trip-backend/src/test/java/com/smarttrip/AiWorkflowIntegrationTests.java` | 迁移旧 GENERATE 测试到确认需求接口，补齐固定响应时间/概要；新增真实隔离 Redis 丢锁及旧令牌释放回归。 |
| `smart-trip-backend/src/test/java/com/smarttrip/AiGenerationPreviewTests.java` | 单任务 JDBC 夹具准确模拟新增的排除当前任务查询。 |
| `smart-trip-backend/src/test/java/com/smarttrip/AiRagWorkflowTests.java` | 同步离线 JDBC 夹具的用户行锁和活跃任务查询语义，不删除原断言。 |
| `smart-trip-backend/src/test/java/com/smarttrip/AiRagHttpClosureIntegrationTests.java` | 复用原 HTTP 闭环，新增真实 BGE/ES/MySQL/Redis＋固定 Agent 响应的工具、阶段顺序及保存回归。 |
| `smart-trip-backend/src/test/java/com/smarttrip/agent/TravelAgentOrchestratorTests.java` | 增加安全 JSON 包装、超时、中断、中途失败、最后一轮收尾及预算 Schema 断言。 |
| `smart-trip-backend/src/test/java/com/smarttrip/agent/TravelAgentToolTests.java` | 增加小数整数拒绝、空/超长/非法文本、人数天数边界和币种冲突检查。 |
| `smart-trip-backend/src/test/java/com/smarttrip/agent/TravelAgentProviderTransportTests.java` | 真实本机 HTTP 验证最后一轮不发送工具定义；HTTP 400 不支持工具时失败且无重试。 |

首次独立模型探测的摘要原文没有保留，不能倒推出究竟是哪种呈现差异。仅兼容完整包装的修复有本地回归支持，之后的真实摘要及闭环均成功；没有声称重放了该次缺失的原文。旧预算失败也只保留了参数校验错误，没有保存原始参数，因此不臆测具体错误值；本轮补齐 Schema、白名单指导并通过后续真实预算调用验证。

旧 9 个环境测试的问题为废弃 GENERATE 入口及不再满足保存要求的固定响应；更新夹具到现行契约，保留确认、版本、退款和保存断言，未恢复被禁止的旧入口。

## 12. 数据与资源隔离

- 正式库的业务表及迁移版本经前后对照一致。测试库完成既有迁移后已清理本轮合成数据，原有业务记录保持一致。
- 试验检索索引和临时 Redis 使用独立资源。正式知识库的别名、内容指纹、8 份文档和 16 个切片保持一致。
- 本机运行配置和凭据留在各自环境中。验收只保存必要的脱敏计数、状态和断言。

## 13. 最终启动方式与开关

在项目根目录使用现有 Windows PowerShell 5.1 入口：

~~~powershell
.\scripts\Start-Backend.ps1
~~~

脚本复用本机原 MySQL、Redis、Ollama、ES，并在内存读取现有 ES 凭据；本轮没有改写这些配置。普通入口不等同于关闭付费模型的 RAG 诊断入口。

Agent/MCP 默认关闭。用户需要主动启用完整功能时，在自己的开发进程设置：

~~~powershell
$env:SMARTTRIP_AGENT_ENABLED='true'
$env:SMARTTRIP_AGENT_TOOL_CALLING_VERIFIED='true'
$env:SMARTTRIP_AGENT_VERIFIED_MODEL='qwen3.8-flash'
$env:SMARTTRIP_AGENT_VERIFIED_BASE_URL='https://dashscope.aliyuncs.com/compatible-mode/v1'
$env:SMARTTRIP_MCP_ENABLED='true'
.\scripts\Start-Backend.ps1
~~~

以上是供用户后续主动启动的命令，本轮未对正式库执行这些启动操作。AI 的 enabled、free-quota-verified 和 Key 校验继续保留；关闭 Agent 时继续原 RAG＋Provider 流程。MCP 只在明确启用后出现在回环地址的 /api/mcp，需要现有登录 JWT。后续正常生成属于新的真实模型操作，不包含在本轮已结束的授权中。

前端沿用 `smart-trip-frontend` 下的 `npm run dev`。本轮临时验收进程已退出，没有留下付费模型启用的验收服务。

## 14. 未验收与限制

1. **景点/天气真实正向查询未验收**：用户明确没有配置 Key。需具备有效供应商凭据并确认权限/额度后再联调；不能用现有不可用结果替代该验收。
2. 不证明自然语言特殊限制、实时票价、营业时间、真实交通与天气的语义完全正确；Evaluator 是结构化条件/证据一致性检查，不是旅行事实全认证。
3. 本轮只验收本机受控环境，未做公网 MCP 部署、生产压测或全量移动端视觉回归；实际运行 JDK 为 21，编译目标保持 17。
4. 部分故障用本机 HTTP 固定响应、Mock 或隔离数据模拟；仅正向真实供应商/真实本机服务项声明为真实联调通过，不将故障夹具计为供应商故障实测。

## 15. 如何复核

- Agent 源码和测试见 [Agent 与 MCP 使用说明](AGENT-MCP-V2.md)。可重跑 TravelAgentOrchestratorTests、TravelAgentProviderTransportTests、TravelAgentToolTests 和 TravelMcpProtocolTests。
- AI 任务与知识快照可通过 AiRagHttpClosureIntegrationTests、AiGenerationClosureIntegrationTests 和 AiWorkflowIntegrationTests 重跑。真实检索的配置和评测入口见 [RAG 运维文档](RAG-OPERATIONS.md)。
- 本地截图、供应商响应、原始请求、数据库指纹和本机构建日志保留在独立的验收环境中。上文的结果表为可公开的核对记录。

**最终结论：核心功能通过，但存在明确遗留项。** 景点和天气的真实正向接口未验收，因此本报告不宣布 SmartTrip V2 全部验收完成。
