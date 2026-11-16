# SmartTrip Agent 与 MCP V2

本轮保留 Java 17、Spring Boot 4.0.8、Spring AI 2.0.1。新增的唯一生产依赖是同版本 `spring-ai-starter-mcp-server-webmvc`；没有 MCP Client Bean，也不通过 HTTP 回调自身。内部 Agent 和外部 MCP 共同调用 `TravelAgentToolService`。

## 调用链与边界

确认需求 → 原 `AiTaskService` 创建 GENERATE 任务 → 原用户分布式锁与一次任务配额预留 → 原 RAG 检索与 `AiTaskStore.recordRagContext()` 持久化 → `TravelAgentService` → `TravelAgentOrchestrator` → 手动 `ChatClient` / `ToolCallingManager` 循环 → `TravelAgentEvaluator` → Agent 快照落库 → 原 `AiProvider.complete()` 严格 JSON → 原标准化/校验 → PREVIEW → 原草稿编辑与 APPLY → 原工作台。

Agent 关闭时仍走原 RAG + JSON 生成。INTERPRET、MODIFY、ADJUST 不进入 Agent。已有 requestKey 重试先返回原任务，不要求重新启用 Agent。只有新的、已确认的 GENERATE 才做 Agent 就绪校验。

原生成客户端保留 JSON_OBJECT、无重试和原解析契约。独立工具客户端复用同一 AiProperties 和单次 OkHttp 安全传输，但不设 response_format。`AdvisorParams.toolCallingAdvisorAutoRegister(false)` 关闭自动循环；应用检查每个 ChatResponse 后才调用 ToolCallingManager，工具结果作为 ToolResponseMessage 真正送入下一次模型输入。

默认限额：4 次 Agent 模型请求、5 次工具调用、含最终 JSON 的总模型请求最多 5 次；Agent 90 秒、单工具 10 秒、单工具结果 4000 字符。未知工具、重复参数、截断摘要、超限均有明确处理，不隐式补发修复模型请求。模型传输和工具执行都受截止时间限制，工具工作队列有界。生成任务锁租期覆盖 RAG、Agent 和原最终生成时间预算，完成时仍比较 token 后释放原锁。

Evaluator 检查目的地、天数、人数、预算口径/币种、必去/避开条件是否被保留、已知预算超额、天气证据与预报日期、知识来源 ID。不能可靠确认的语义或实时信息作为警告，继续由原最终草稿校验和用户编辑处理，不改写确认条件。

## 四个工具

| 名称 | 输入 | 实际调用与输出 |
| --- | --- | --- |
| searchAttractions | city、keyword，均至多100字符 | 原 GeoService.search；只返回实际响应白名单字段，最多5个地点，提供来源与查询时间；不声称核实价格 |
| getWeatherForecast | city、ISO date | 先校验今天至未来3天及已确认旅行日期，再调用原 GeoService.weather；来源未启用或日期不适用明确返回不可用 |
| calculateBudget | travelers、days、currency、costScope；可选交通/住宿/餐饮/门票/其他费用及预算上限/口径 | BigDecimal 计算；每项表示整段旅行按 TOTAL 或 PER_PERSON 的合计，不是每日价格；输出已知总额、人均、明细、假设、超额判断；缺费用保持未知，确认人数未知时不编造 |
| getTravelKnowledge | 内部可省略 taskId；MCP 必须给本人已有任务 ID | 只读已保存 RAG，上下文不重新检索；白名单返回来源、有限摘录和适用期；再次校验年度有效期；不返回完整私有 Prompt |

费用不是实时报价，不自动换汇。不包装依赖已保存 tripId/dayId 的路线接口，不存在保存、删除、发布、预订或支付工具。

## 持久化、SSE 与用量

复用 `ai_task.proposal_meta_json`，不新增迁移、不修改 V1–V10：

- `agentEnabled`、`agent.status/summary/traces/evaluation/sources/toolSources` 是本人可查看的脱敏状态。
- 每条 trace 保存序号、工具名、RUNNING/完成状态、耗时、固定脱敏摘要、错误码和时间，不保存原始参数。运行中与完成记录按序号更新。
- `modelCalls` 在请求前记录 STARTED，结束后记录模型名、操作、次数、实际 Token 用量及耗时；最终 JSON 调用也使用同一账本。重复完成同一序号不重复计数。
- `modelUsage` 汇总已知用量；未返回的 Token 标记不完整，未配置价目时 estimatedCost 为 null，不把未知费用记成零。免费任务配额沿用原一次预留与幂等退款，模型请求另受每任务次数上限约束。
- `_agentContext`、原 `_ragContext` 和 `_modelDiagnostics` 不投影至任务 API。最终 Prompt 中参考资料和工具数据都有明确的不可信边界。

原任务仍使用 QUEUED/RUNNING/PREVIEW/APPLIED/FAILED。新增 AGENT_ANALYZING、AGENT_TOOL_CALLING、AGENT_TOOL_COMPLETED、AGENT_EVALUATING、AGENT_COMPLETED 是 generation.phase，继续由原 SSE 和轮询传输。前端只在原生成进度和草稿里显示工具记录、来源类别与评估；不改需求字段、导航或保存动作。

## 默认配置与人工启用

生产默认 `smarttrip.agent.enabled=false`、`smarttrip.mcp.server.enabled=false`。未验证 Tool Calling 的当前模型不得设 READY。

Agent 的进程环境变量：

```powershell
# 只有在另行授权并核实供应商能力后才填写这些声明；不会自动探测模型。
$env:SMARTTRIP_AGENT_ENABLED = 'true'
$env:SMARTTRIP_AGENT_TOOL_CALLING_VERIFIED = 'true'
$env:SMARTTRIP_AGENT_VERIFIED_MODEL = '<实际已验证模型ID>'
$env:SMARTTRIP_AGENT_VERIFIED_BASE_URL = '<实际已验证OpenAI兼容Base URL>'
```

两个 verified 字段必须与现有 AiProperties 完全对应（地址仅忽略末尾斜线）；同时保留原 enabled、free-quota-verified 和 API Key 检查。`GET /api/capabilities` 的 `agent` 单独报告 DISABLED / TOOL_CALLING_UNVERIFIED / READY 等状态。不要因固定响应测试通过而填写供应商验证声明。

2026-11-05 只读核对的本机模型是 `qwen3.8-flash`，接口为百炼 compatible-mode/v1。[阿里云官方模型说明](https://www.alibabacloud.com/help/en/model-studio/qwen3-8-flash)列明支持 Function Calling；本轮没有调用该供应商，账户权限、地域、额度和实际响应仍未验证，未自动填写 verified 声明。

可选费用估算由 `smarttrip.agent.pricing-model/price-currency/input-price-per-million/output-price-per-million` 配置，只对模型名匹配的调用生效。费率须由操作者核实，不能猜测或填入真实密钥。按每次已返回 Token 用 BigDecimal 估算，模型调用账本与汇总都保留费用、币种和 costStatus；缺费率、模型不匹配、供应商未返回用量时总费用保持未知，并单独保留已知部分。该估算不代表供应商账单，不自动换汇。

其他安全限额使用 `smarttrip.agent.max-agent-model-turns/max-tool-calls/max-total-model-calls/max-execution-seconds/tool-timeout-seconds/max-tool-result-characters`。过大或互相矛盾的配置拒绝执行。

## MCP 连接与认证

启用 MCP 不要求启用 Agent，也不会调用 ChatClient：

```powershell
$env:SMARTTRIP_MCP_ENABLED = 'true'
.\scripts\Start-Backend.ps1
```

沿用原本机服务与安全启动脚本、JWT 密钥和登录接口。端点为 `http://127.0.0.1:8080/api/mcp`，协议为 STREAMABLE，不注册旧 `/sse` 传输。Spring AI 自动配置和业务开关保持一致；不一致、协议/路径被改成不受保护入口、缺少明确回环绑定时拒绝启动。受控私网仅在 `allow-private-network=true` 且绑定具体私有 IP 时允许，不能绑定 0.0.0.0。

支持 Streamable HTTP 的客户端应在初始化、发现工具、调用和后续会话请求中都发送 `Authorization: Bearer <当前登录JWT>`。使用真实登录得到的令牌，不能把 API Key 当令牌。客户端连接示意（字段以所用客户端规范为准）：

```json
{ "url": "http://127.0.0.1:8080/api/mcp", "headers": { "Authorization": "Bearer <当前登录JWT>" } }
```

MCP 工具参数的外层为 `{"input": {...}}`，例如 calculateBudget：

```json
{"input":{"travelers":2,"days":3,"currency":"CNY","costScope":"TOTAL","transport":400,"accommodation":600,"food":300,"tickets":100,"other":0}}
```

Servlet Filter 保护函数式路由：真实 JWT 校验账户状态/tokenVersion、每请求授权、Redis 用户级每分钟限额（默认30）、会话与用户绑定、拒绝浏览器 Origin、限制请求大小。认证后的用户 ID 通过服务端 TransportContext 传递给异步 MCP 方法，不把 JWT 传给工具或模型。Redis 故障返回503，不能静默绕过限流。关闭时端点404；未认证401，其他用户会话403，限流429。

只允许公开来源；getTravelKnowledge 另经 AiTaskStore 的本人任务校验。不存在 MCP 写入能力。外部客户端与真实数据库/Redis 的部署仍需在运行环境中另行联调；协议测试使用真实 HTTP 与官方 SDK 客户端，外部账户、存储和天气响应使用测试夹具。

## 验证命令与证据边界

```powershell
# Maven 使用项目原仓库配置；所有收费模型与需要真实数据库的集成开关保持关闭。
cd smart-trip-backend
mvn -s ../scripts/maven-settings.xml -gs ../scripts/maven-settings.xml test
cd ../smart-trip-frontend
npm run test
npm run typecheck
npm run build
```

新增测试覆盖真实 ToolCallingManager、固定 ChatModel 响应、真实 Spring AI 客户端到本地固定 HTTP 服务、模型/工具限额与超时、RAG先落库、实际工具结果进入下一轮和最终Prompt、编辑保存/幂等/退款、独立官方 MCP SDK 的 HTTP 发现调用与 JWT 边界，以及前端脱敏展示。真实供应商 Tool Calling、真实收费生成及未启动的数据库/ES/Redis 集成不计作通过。

实现依据：[Spring AI 2.0 Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)、[Streamable MCP Server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)。实际 API 以本项目解析到的 2.0.1 JAR 为准，未使用文档页面较新版本替换依赖。
