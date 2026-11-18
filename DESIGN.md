# claim-pro 设计文档（DESIGN）

版本：v1.1.0 ｜ 日期：2026-11-18 ｜ 状态：定稿（与 REQUIREMENTS.md v1.1.0 配套）
口径：**商用生产架构**（非本地内置版）；消息层 **RocketMQ**（替代 Kafka）；编排 Temporal；agent 框架 LangChain4j；主力模型 DeepSeek。
v1.1.0 变更：新增 §11 业务流程设计详解（P0 实现如实描述，含每条流程的设计方案/步骤/实现位置/与生产差异）、§12 验证结果；§10.4/10.5 对齐当前交付现状。

**文档地图**：[REQUIREMENTS](REQUIREMENTS.md)（需求与验收映射）· **DESIGN**（本文：目标架构 + 实现详解）· [OPERATIONS](OPERATIONS.md)（操作手册）· [COLLABORATION](COLLABORATION.md)（协作决策复盘）· [README](README.md)（入口与快速开始）

---

## 1. 设计原则

1. **权力分层**：agent 产出事实/建议/解释/质疑；资格判定、金额、审批生效、资金划转归确定性核心；流程走向归 Temporal。LLM 的不确定性只存在于节点内部，不存在于流程与资金上。
2. **双网关收口**：工具调用一律经工具网关，模型调用一律经模型网关。审计、脱敏、限流、计量在网关层强制，不依赖业务自觉。
3. **记录系统边界**：保险核心系统是 System of Record；本系统只产出建议书，经审批后由核心既有接口执行登记。
4. **框架效率 + 自研控制**：LangChain4j 做 agent 编程模型（AiServices/@Tool/Guardrails/RAG），控制点（路由/脱敏/熔断/计量/审计）全部落在自定义 ChatModel 适配器与网关内。禁止 `OpenAiChatModel` 直配 apiKey 的 demo 用法。
5. **可回放**：每步决策带 trace_id、证据哈希、模型/prompt 版本，WORM 存档≥10 年。

## 2. 总体架构

```
渠道(App/话务/修理厂/开放API)
   │ API网关(OAuth2+mTLS)
   ▼
理赔编排域  Temporal 集群
   │  案件=workflow实例；activity=agent调用/规则/人工任务
   │  Task Queue 按 agent 类型分队列（持久化派发/重试/背压/隔离）
   ▼
Agent执行域  无状态 worker 池(K8s HPA 按队列深度)
   │  LangChain4j AiServices ×8（独立部署组/资源配额/发布节奏）
   ├──────────────┬───────────────────────┐
   ▼              ▼                       │
工具网关        模型网关                    │
(鉴权/限流/     (多端点路由/脱敏前置/        │
 审计/幂等/      熔断/语义缓存/计量/         │
 核心系统适配)   prompt版本/内容安全)        │
   │              │                       │
   ▼              ▼                       ▼
确定性核心域     模型层                  数据/事件层
规则引擎(Drools) DeepSeek flash(对话/抽取) MySQL集群(案件)
理算计算器(纯代码) DeepSeek chat(推理/复核)  RocketMQ(事件+事务消息)
审批流(分级授权)  私有VL(vLLM,影像不出域)    Redis(会话/缓存/锁)
支付服务(双账本)  OCR专用模型前置           ES+Milvus(条款/相似案件)
                XGBoost+图谱(欺诈,非LLM)   NebulaGraph(团伙关系)
                                         MinIO WORM(证据≥10年)
横切：OTel+SkyWalking · Prometheus/Grafana · 审计事件 · 金标集回归 · KMS
```

## 3. 技术选型（定稿）

| 层 | 选型 | 备注 |
|----|------|------|
| 语言/框架 | Java 21 + Spring Boot 3.2.x + Maven 多模块 | 与 cs-pro 基线一致；虚拟线程适配 IO 密集 |
| Agent 框架 | **LangChain4j 1.x**（core/open-ai/所需 store 模块，不引全家桶） | BOM 锁版本；升级走金标集回归；CVE 扫描进 CI |
| 主力模型 | DeepSeek 官方 API（OpenAI 兼容 `https://api.deepseek.com/v1`） | flash=对话/抽取；chat=推理/复核；**官方模型 ID ≠ 第三方平台 ID** |
| 影像/敏感 | DeepSeek-VL2 私有化（vLLM）；OCR 专用模型前置 | 影像/病历不出域；协议统一，迁移只改路由表 |
| 欺诈评分 | XGBoost/LightGBM（JPMML）+ NebulaGraph 图特征 | 非 LLM，可解释可年度验证 |
| 编排 | **Temporal**（Java SDK） | 长周期案件、人工 signal、流程版本灰度 |
| 消息 | **RocketMQ 5.x**（替代 Kafka） | 事件流 + 支付事务消息（半消息），见 §5 |
| 案件库 | MySQL 8 + ShardingSphere（机构+年份分片） | 与核心系统单向同步 |
| 缓存/锁 | Redis 7 集群（Redisson） | 敏感字段不落缓存 |
| 检索 | ES 8 + Milvus | 条款按出险日取版本 |
| 图谱 | NebulaGraph | T+1 批量+实时增量；P0 关系库存储全量重建（§11.18） |
| 证据 | MinIO/云 OSS + WORM 合规保留 | 哈希链防篡改 |
| 规则 | Drools（条款规则包，带生效日期） | 核赔师可参与维护 |
| 可观测 | OTel + SkyWalking + Prometheus/Grafana + Loki | trace_id 贯通 workflow→agent→工具→模型 |
| 批处理 | XXL-Job | 日终对账/图谱构建/金标回归；已落地 XXL-Job 2.4.1 调度中心（Docker :8094，ops-pro 纳管）+ 内嵌执行器 :9999 + 启动跑 + 手动 API；local 档位 Spring cron 兜底（§11.18/§11.19） |
| 部署 | K8s + Helm；开发/预发 docker-compose | 同城双活，异地质检 |
| 密钥 | 生产 KMS；开发 `~/.config/claim-pro/`（700/600） | 仓库只留 *.example |

## 4. 编排设计（Temporal）

### 4.1 工作流与队列

```java
@WorkflowInterface
public interface ClaimWorkflow {
    @WorkflowMethod void run(ClaimInput input);          // 案件主流程，workflowId=案件号
    @SignalMethod void onMaterialsSubmitted(MaterialBatch b);  // 补件
    @SignalMethod void onHumanDecision(HumanDecision d);       // 审批/拒赔确认/抽检改判
    @SignalMethod void onCustomerDispute(Dispute d);           // 异议升级
    @QueryMethod ClaimState state();
}
```

| Task Queue | Worker 组 | 说明 |
|-----------|----------|------|
| claim-intake | 受理/沟通 | 对话类，flash |
| claim-doc | 识别 | 大流量，独立限流 |
| claim-assess | 定损/理算建议 | |
| claim-review | 独立复核 | 物理隔离部署，只读工具 |
| claim-fraud | 欺诈汇总 | |
| claim-core | 规则/计算器/审批/支付 activity | 确定性核心，LLM 禁入 |

### 4.2 关键机制
- **人工节点**：activity 发起审批任务后 workflow `await(signal)`，durable timer 超时自动升级；
- **并行**：反欺诈 ∥ 定损，聚合门禁后分级路由；
- **版本灰度**：`Workflow.getVersion` 分支，新流程只接新案件，在途案件按旧版本跑完；
- **降级**：模型网关熔断全开 → activity 返回 DEGRADED → workflow 转纯人工通道，不停摆；
- **幂等**：activity 幂等键=案件号+步骤号+批次号；支付重复请求返回原结果。

## 5. 消息层设计（RocketMQ，替代 Kafka）

### 5.1 职责划分

| 职责 | 承载 | 说明 |
|------|------|------|
| 编排→agent 任务分发 | **Temporal Task Queue** | 不用 MQ 重复建设 |
| 决策/审计事件流 | RocketMQ 普通消息（顺序，按案件号分区） | 消费方：审计归档、数仓、指标 |
| 支付一致性 | **RocketMQ 事务消息（半消息）** | 本地事务=账本落库；回查=查账本状态 |
| 通知/异步任务 | RocketMQ 普通消息 | 客户通知、标注回流 |

### 5.2 Topic 规划

| Topic | 类型 | 消息键 | 消费方 |
|-------|------|--------|--------|
| claim-decision-events | 顺序消息 | 案件号（MessageQueueSelector） | 审计归档(WORM)、数仓、指标 |
| claim-pay-tx | 事务消息 | 案件号+赔付批次 | 支付执行、核心系统登记 |
| claim-notify | 普通 | 案件号 | F8 沟通、短信/推送网关 |
| claim-feedback | 普通 | 案件号 | 人工改判回流→标注平台 |

### 5.3 支付事务消息流程

```
1. 发送半消息(claim-pay-tx) ── broker 暂存不可见
2. 执行本地事务：双账本记账 + outbox 落库（同库同事务）
3. COMMIT → 消息可见 → 支付执行器划款 → 核心登记
   ROLLBACK → 消息丢弃
4. broker 回查（网络断/进程崩）：查 outbox/账本状态 → 补 COMMIT/ROLLBACK
5. 日终三层对账兜底：账本 vs 银行回执、本系统 vs 核心、建议 vs 计算器
```

### 5.4 审计事件 schema（claim-decision-events）

```json
{ "traceId": "...", "caseNo": "...", "step": "ASSESS", "actor": "agent-assess",
  "modelVersion": "deepseek-chat@2026-11", "promptVersion": "assess-v3",
  "inputHash": "sha256:...", "evidenceRefs": ["ev-001"],
  "output": {...}, "confidence": 0.92, "tokens": {"prompt": 1800, "completion": 400},
  "ts": "2026-11-18T10:00:00Z" }
```

## 6. Agent 层设计（LangChain4j）

### 6.1 集成点（全部走框架正式扩展位）

```java
// 控制点收口：自定义 ChatModel，内部委托模型网关客户端
public class GatewayChatModel implements ChatLanguageModel, StreamingChatLanguageModel {
    private final LlmGatewayClient gateway;   // 路由/脱敏/熔断/重试/缓存/计量
    private final String agentType;           // → 路由表选端点
}

// agent 声明式定义（⑦复核示例）
ReviewAgent agent = AiServices.builder(ReviewAgent.class)
    .chatModel(new GatewayChatModel(gateway, "review"))
    .tools(reviewTools)                        // @Tool 方法体内强制走工具网关
    .chatMemoryProvider(id -> redisChatMemory(id))
    .contentRetriever(clauseRetriever)         // 条款 RAG，按出险日取版本
    .guardrails(complianceGuardrails)          // 输出不得含赔付承诺
    .build();
```

- `ChatModelListener`：onResponse → 证据快照(WORM) + token 计量 + OTel span；
- `ToolProvider` SPI：案件级动态授权（复核只读；定损不可碰支付类工具）；
- 结构化输出：JSON Output 模式 + POJO 映射 + Jackson 校验，低置信度转人工。

### 6.2 DeepSeek 路由表（模型网关配置）

| agentType | 端点 | 模型 | 说明 |
|-----------|------|------|------|
| intake / notify | 官方 API | deepseek-flash | 高频对话 |
| doc-extract | 官方 API | deepseek-flash + JSON mode | OCR 文本后结构化 |
| doc-vision | 私有 vLLM | deepseek-vl2 | 影像不出域 |
| assess / coverage-explain | 官方 API | deepseek-flash | 估价依据/条款解释 |
| review / calculation-draft | 官方 API | deepseek-chat | 强推理，量小 |
| fraud-report | 官方 API | deepseek-chat | 线索汇总成报告 |

端点按 baseUrl+model 显式配置，不做跨平台名称翻译。长 system prompt（条款库）固定前缀，利用 DeepSeek 上下文缓存计费优惠。

### 6.3 模型网关客户端（移植 cs-llm 已验证模式）

保留：JDK HttpClient 零 SDK、按端点熔断（连续 5 败熔 60s，half-open 探测）、分类退避重试（429/5xx/网络≤2 次；4xx 与已出流不重试）、流式/工具调用双模式、usage 计量。
新增：多端点路由、故障切换备用端点、脱敏前置（身份证/银行卡/手机号）、语义缓存（Redis）、prompt 版本注入、内容安全过滤、成本落账到案件。

## 7. 确定性核心域（LLM 禁入）

| 组件 | 设计 |
|------|------|
| 条款规则引擎 | Drools 规则包，按出险日取条款版本；输出命中条款/免责项，供 F3 解释 |
| 理算计算器 | 纯 Java：免赔/折旧/限额/比例；与 F6 草案双跑比对，差异落监控 |
| 审批流 | 额度分级授权；>10 万双人；拒赔必须人工确认 |
| 支付服务 | 持牌通道/银企直连；双账本（业务账+资金账）；幂等键=案件号+批次；日终对账+银行回执核对+长短款挂账+差错处理台 |

## 8. 数据模型（核心表）

```
claim_case        案件主数据(case_no PK, 状态, 通道, 机构, 险种, 出险日, 金额, 风险分)
claim_material    材料(material_id, case_no, 类型, 对象存储键, 哈希, 置信度)
claim_evidence    证据快照(evidence_id, case_no, step, input_hash, output_ref,
                  model_version, prompt_version, ts)  → WORM 对象存储
claim_decision    决策事件(与 RocketMQ 事件同构, 本地 outbox 副本)
claim_approval    审批(approval_id, case_no, 级别, 审批人, 结论, ts)
ledger_business   业务账(案件维度应收应付)
ledger_fund       资金账(渠道维度实收实付)
pay_outbox        支付本地事务表(幂等键, 状态, 事务消息回查依据)
recon_diff        对账差异(三层对账结果, 差错处理状态)
clause_version    条款版本(条款号, 生效区间, 文本, 规则包引用)
graph_case_shop   案件↔修理厂关联登记(case_no, shop_name, source, ts；报案/定损登记)
repair_shop_flag  修理厂风险标记(case_no, shop_name, reason, source=MANUAL|GRAPH_BATCH)
graph_node        图谱节点(node_type CASE/POLICY/SHOP, node_key, props_json, ts)
graph_edge        图谱边(src/dst type+key, edge_type INSURED_BY/REPAIRED_AT, ts)
graph_build_run   图谱构建运行(ts, trigger_by, 节点/边/厂数, 新增标记数, 耗时)
gold_run          金标回归运行(ts, trigger_by, total/passed/failed, 耗时)
gold_case_result  金标逐案结果(run_id, gold_id, passed, mismatches_json)
```

分片：claim_* 按机构+年份；账本按机构。审计事件 RocketMQ→数仓保留≥10 年，本地 outbox 副本滚动 90 天。

## 9. 可观测与 LLMOps

- trace_id 贯通：API→Temporal workflow→activity→agent→工具网关→模型网关；
- 核心指标：人工推翻率（>5% 告警并降级该通道）、置信度分布、熔断状态、单案件 token 成本、通道时效；
- 发布流水线：prompt/模型/框架版本变更 → 金标集回归（≥2000 案件）→ 影子 2–4 周 → 灰度 5%→25%→100%；
- 人工改判经 claim-feedback 回流标注平台，驱动微调/规则修订。

## 10. 工程结构与部署

### 10.1 Maven 多模块

```
claim-pro/
├── REQUIREMENTS.md / DESIGN.md / OPERATIONS.md / COLLABORATION.md / README.md
├── claim-common          # 案件模型/幂等键/审计事件/端口接口
├── claim-llm             # 模型网关客户端 + GatewayChatModel + 路由/脱敏/计量
├── claim-agent-runtime   # AiServices 装配/工具注册/Guardrails/记忆
├── claim-agents          # agent 接口与 @Tool 工具集（P0 六门面，见 §11.15）
├── claim-core            # 规则引擎/理算计算器/审批/双账本（LLM 禁入）
├── claim-workflow        # Temporal workflow/activity 定义与 worker + 本地开发管道
├── claim-mq              # RocketMQ 事件发布/事务消息/消费者
├── claim-infra           # MySQL/Redis/ES/Milvus/MinIO/图谱 适配器
├── claim-gateway         # 工具网关 + 开放 API（Spring Boot 主应用, 端口 8097）
├── deploy/               # docker-compose(开发/预发) + helm(生产)
├── config/*.example      # 真实密钥 ~/.config/claim-pro/（700/600）
└── run.sh / stop.sh / smoke-test.py
```

### 10.2 基础设施编排（deploy/docker-compose.yml，开发/预发）

| 服务 | 镜像 | 端口 |
|------|------|------|
| temporal | temporalio/auto-setup | 7233（UI 8088） |
| rocketmq-namesrv | apache/rocketmq | 9876 |
| rocketmq-broker | apache/rocketmq | 10911 |
| rocketmq-dashboard | apacherocketmq/rocketmq-dashboard | 19876 |
| mysql | mysql:8 | 3306 |
| redis | redis:7 | 6379 |
| minio | minio/minio | 9000（console 9001） |

ES/Milvus/NebulaGraph 属 P1+ 阶段，届时追加；P0 条款检索可先 MySQL 全文索引过渡。

### 10.3 生产部署

K8s + Helm：每类 agent 独立 Deployment（HPA 按 Task Queue 深度）；Temporal/RocketMQ/MySQL 同城双活；模型网关多副本；私有 vLLM GPU 池独立命名空间。RPO≈0（半同步复制）、RTO<30min。

### 10.4 本机开发约束（如实标注，2026-11-18 现状）

本机已装 Docker Desktop（引擎 29.9.3/Compose 5.7.0）：中间件统一由 **ops-pro（8098）** 的 claim-shared compose 栈管理（Temporal/RocketMQ×3/MySQL/Redis/MinIO/ZooKeeper×2/**XXL-Job 调度中心 :8094**），claim-pro 与 cs-pro 共用 MySQL/RocketMQ/Redis。MinIO 社区版上游已停止预编译分发，镜像由 **GitHub 源码多阶段构建**（配方 ops-pro/deploy/minio-from-source.Dockerfile，S3 往返实测通过）。应用编译用 mvnw。

**三档 profile（2026-11-18 起默认 mysql）**：
- `local`：零依赖开发，H2 文件库 + 本地文件系统证据库 + LocalLogEventPublisher + LocalPipelineRunner；
- `mysql`（默认）：**MySQL claim 库**（claim-shared-mysql，专用账号 claim，口令 `~/.config/claim-pro/claim.properties`）+ 本地编排/事件/证据件 + **XXL-Job 执行器**（调度中心 claim-shared-xxl-job-admin :8094，accessToken 同文件）；库表由 `deploy/mysql/001_schema.sql` 初始化（20 表，`sql.init.mode=never`），种子 `002_seed.sql`；H2→MySQL 一次性迁移工具 `deploy/mysql/MigrateH2ToMysql.java`（H2 只读、逐表行数校验、失败回滚，原 H2 文件保留作备份）；
- `default`：全栈生产档位，Temporal/RocketMQ/MinIO 全启用，须由 ops-pro 先起中间件。

无中间件时以 `CLAIM_PROFILE=local` 启动，启动即告警非生产编排。DeepSeek 联调需 `~/.config/claim-pro/` 配置 Key，未配置时 AI 端点 503、流程走人工降级通道。local/mysql 档位静态资源从源码目录热加载且强制 `Cache-Control: no-cache, must-revalidate`（防浏览器缓存旧样式）。

### 10.5 P0 实现简化清单（与目标架构的差异，如实标注）

- **单 Task Queue**：P0 所有 activity 注册在 `claim-main` 单队列，4.1 的六队列物理隔离属 P1 部署拆分；
- **本地开发管道**：`claim.temporal.enabled=false` 时由 LocalPipelineRunner 以同构状态机跑流程，人工节点轮询 claim_approval 表（2s）代替 signal，等待窗口 30 分钟；重启后经 ApplicationReadyEvent 按案件状态续接等待节点（REPORTED/MATERIALS_PENDING/APPROVING/MANUAL），其余中间态告警不自动续接——生产一律 Temporal durable 恢复，无此限制（详见 §11.16）；
- **欺诈评分**：P0 为确定性规则评分器（金额/历史/修理厂标记/深夜报案加权，§11.4），非模型推理；反欺诈 agent 仅做报告汇总；
- **报案结构化**：P0 为表单直填 + 可选 LLM 结构化增强（`/api/agent/intake`，503 降级不影响建案）；关键词提取用于确定性核心的免责事由与责任比例判定（§11.3/§11.6），非 LLM 职责；
- **识别置信度**：P0 定损后固定置 0.9（无独立识别模型），生产由 F2 识别模型输出真实置信度并执行 <0.85 转人工；
- **理算草案双跑**：P0 仅计算器单跑，F6 LLM 草案与双跑比对监控属 P1；
- **支付**：P0 为模拟支付 activity + 双账本落库（事务消息链路代码完整，local 无 MQ 时直连账本），真实渠道对接属 P1；
- **条款检索**：P0 无向量库，核保走确定性规则引擎（免责条款表）；
- **复核**：P0 review agent 为按需 API（详情页「AI 复核」），未嵌入主流程强制门禁，F7 质疑自动触发深查属 P1；
- **批处理调度**：mysql/default 档位已接 **XXL-Job 2.4.1**（调度中心 Docker :8094，执行器 claim-pro-executor :9999，图谱 02:30 / 金标 03:00，trigger_by=XXL_JOB）；local 零依赖档位由 Spring `@Scheduled` 兜底（`claim.batch.*-cron` 可配，条件装配防双调度）；启动各跑一次 + 手动 API 保留，失败不阻断主链路；XXL-Job 分片广播属 P1（§11.18/§11.19）；
- **图谱存储**：P0 节点/边落关系库（graph_node/graph_edge）T+1 全量重建，修理厂关联来自 graph_case_shop 登记；生产 NebulaGraph + 实时增量属 P1；标记规则与回写语义不变；
- **金标集规模**：P0 为 15 条核赔师标注案（gold-cases.json，覆盖三通道/三类法定免责/责任比例/限额/免赔/审批分级），纯代码重放不触碰 LLM；生产 ≥2000 案 + 影子双跑属 P1。

## 11. 业务流程设计详解（P0 实现，as-built）

> 本节按代码如实描述每条业务流程的**设计方案、执行步骤、实现位置与生产差异**。
> 总原则贯穿所有流程：**状态迁移只由编排代码决定；金额只由计算器/人工决策决定；agent 输出只作建议与解释；每一步都落决策事件 + 证据哈希链。**

### 11.0 主流程总览与状态机

```
报案 POST /api/claims（同步建案，API 返回前案件行必存在）
  │
  ▼
材料齐套门禁 ─不齐→ MATERIALS_PENDING（等补件 signal / 本地 2s 轮询）
  │齐（认定书+照片）        └─ 期间客户异议 → MANUAL
  ▼
COVERAGE_CHECK  规则引擎核保
  ├─免责→ APPROVING（拒赔确认节点）─人工确认→ REJECTED + 通知
  │                              └─未确认/异议→ MANUAL
  ▼属保险责任
ASSESSING  反欺诈评分 ∥ 定损（并行）
  │任一失败（模型/识别不可用）→ MANUAL（显式降级，归档原因）
  ▼
ROUTE  分级路由（risk × 金额 × 置信度）
  ├─ INVESTIGATE / MANUAL → MANUAL（SIU 调查 / 人工处理）
  ▼ FAST / STANDARD
CALCULATE  理算计算器（纯代码）
  ▼
APPROVING  分级审批（FAST 免人工；任一驳回 → REJECTED + 通知）
  ▼ 批准数达标
PAYING  双账本 + 事务消息 → CORE_REGISTER 核心登记 → SETTLED + 通知 → CLOSED
```

**状态字典（12 态）**：REPORTED 已报案 / MATERIALS_PENDING 待补件 / COVERAGE_CHECK 核保中 / ASSESSING 定损中 / ROUTED 已分级 / CALCULATED 已理算 / APPROVING 审批中 / PAYING 支付中 / SETTLED 已赔付 / REJECTED 已拒赔 / MANUAL 人工处理 / CLOSED 已结案。
**通道字典（4 色）**：FAST 快赔 / STANDARD 标准 / INVESTIGATE 调查 / MANUAL 人工。

**双编排同构**：同一套 activity（ClaimActivitiesImpl）被两种编排驱动——生产 `ClaimWorkflowImpl`（Temporal，signal+durable await）与本地 `LocalPipelineRunner`（线程池状态机，轮询 claim_approval 表）。业务规则只写一份在 activity/核心域，编排层只做「等待与迁移」，保证两档位行为一致。

实现：claim-workflow/ClaimWorkflowImpl.java、LocalPipelineRunner.java、ClaimActivitiesImpl.java。

### 11.1 报案受理（FNOL）

**设计方案**：报案是资金链路的起点，必须「API 返回即有案」——同步建案消除竞态（避免返回案件号后查不到行），编排启动异步化。案件号全局唯一可追溯；LLM 结构化是**可选增强**而非前置依赖，未配 Key 不阻塞立案。

**流程**：
1. 前端表单提交：保单号/险种（默认 AUTO）/出险日（默认今天）/事故类型/描述；机构默认 HEAD；
2. `CaseService.report()` 生成案件号 `CLM-YYYYMMDD-XXXXXX`（UUID 前 6 位大写）；
3. 编排启动：Temporal `WorkflowStarter.start()`（workflowId=案件号）或本地 `LocalPipelineRunner.start()`；两者都**先同步 createCase**；
4. `createCase` activity 幂等：案件已存在直接返回（Temporal activity 重试安全）；落 claim_case（REPORTED）+ 归档 REPORT 证据；
5. API 返回案件行；管道异步进入材料门禁。
6. 可选：`POST /api/agent/intake` 调受理 agent 把口语描述转结构化 JSON（accidentType/location/requiredMaterials，不确定填 null 不得编造）；端点不可用返回 503，前端提示降级，不影响报案。

**实现**：claim-gateway/api/CaseService.java:48、ClaimActivitiesImpl.createCase、claim-agents/IntakeAgent.java。
**生产差异**：渠道侧加 API 网关 OAuth2+mTLS；受理 agent 支持对话式多轮 FNOL（P0 为单轮表单+可选结构化）。

### 11.2 材料补件与齐套门禁

**设计方案**：材料是核保/定损的证据基础，齐套判定是**确定性规则**（两类单证齐全），不由模型判断。补件是人工节点：生产用 signal 唤醒，本地用轮询发现，门禁循环可多次往返。

**流程**：
1. 管道检查 `materialsComplete`：存在 ACCIDENT_REPORT（事故认定书）**且** DAMAGE_PHOTO（损伤照片）两类材料；
2. 不齐 → 状态 MATERIALS_PENDING，生产 `Workflow.await(补件signal || 异议)`，本地 2s 轮询（30 分钟窗口）；
3. 客户/工作台补件 `POST /api/claims/{no}/materials`：逐件生成 materialId（案件号-M-XXXXXX）、计算 SHA-256（对 storageKey）、落 claim_material；生产随即发 signal，本地由轮询自然发现；
4. 齐套 → 管道续跑核保；
5. 等待期间客户异议 → 直接升级 MANUAL（见 §11.11）。

**实现**：ClaimActivitiesImpl.materialsComplete:97、CaseService.submitMaterials:74、ClaimWorkflowImpl:44-52。
**生产差异**：F2 识别 agent 对上传影像做 OCR+结构化+置信度（<0.85 转人工录入）；P0 材料为登记制（类型+存储键+哈希），识别属 P1。工作台提供「一键补件（认定书+照片）」演示入口。

### 11.3 核保校验（条款规则引擎）

**设计方案**：拒赔是最高风险动作，**判定权 100% 归确定性规则引擎**，LLM 只做判定结果的条款解释（F3）。规则按「保单有效期 → 承保类型 → 法定免责」顺序短路求值，输出命中条款/免责项/解释键，全部可引用、可回放。免责事由从案件描述做关键词提取（酒驾/醉酒、无证、故意），这是确定性文本规则而非模型推理。

**流程**：
1. 状态 COVERAGE_CHECK；经工具网关 `policy.query` 加载保单快照（有效期/承保类型/限额/免赔/折旧/条款版本）；
2. `CoverageRuleEngine.evaluate()` 依次判定：
   - 出险日不在保单有效期 → 免责 `EXCL-PERIOD`；
   - 事故类型不在承保类型 → 免责 `EXCL-TYPE`；
   - 酒驾/醉酒 → `EXCL-DRUNK`；无证 → `EXCL-UNLICENSED`；故意 → `EXCL-INTENTIONAL`（可叠加）；
   - 通过则命中 `CLAUSE-PERIOD-OK` + `CLAUSE-TYPE-OK`；
3. 归档 COVERAGE 决策（covered/命中条款/免责项）；
4. **不属保险责任** → 状态 APPROVING，进入**拒赔人工确认节点**（§11.10）——规则引擎说了不算，人确认了才算；
5. 属保险责任 → 进入并行评估（§11.4）。
6. 可选：`POST /api/agent/coverage-explain` 把判定 JSON 翻译成客户可读条款解释（引用条款原文，不得自行判定）。

**实现**：claim-core/rule/CoverageRuleEngine.java、ClaimActivitiesImpl.coverageCheck:105。
**生产差异**：内置规则替换为 Drools 规则包适配器（按出险日取条款版本，核赔师可维护），接口不变；免责事由识别由 F2 结构化字段供给而非关键词。

### 11.4 反欺诈评分 ∥ 定损（并行节点）

**设计方案**：两个互不依赖的评估并行执行缩短时效。**欺诈打分是确定性评分器（非 LLM）**——可解释、可年度验证；agent 只消费分数出报告。定损 agent 的价格**必须来自价格库工具**，不得生成价格（工具 schema 里明文约束）。任一节点失败（模型熔断/不可用）→ 显式降级 MANUAL，绝不带病推进。

**流程**：
1. 状态 ASSESSING；生产 `Async.function` 并行两 activity，本地顺序执行（同构语义）；
2. **反欺诈评分** `fraudScore`：经工具网关取 `history.count`（同保单历史已结案件数）、`graph.flagged`（修理厂黑名单标记）；深夜报案=报案时刻<6 点；`FraudScorer.score()` 加权：
   | 因子 | 分值 |
   |---|---|
   | 估损 > ¥50,000 | +25 |
   | 估损 > ¥10,000（≤5万） | +10 |
   | 历史赔案次数 ×10 | 封顶 +30 |
   | 修理厂被标记 | +30 |
   | 深夜报案（<6 点） | +10 |
   总分封顶 100，写回案件 riskScore，归档 FRAUD_SCORE；
3. **定损** `assess`：组装上下文（案件+材料+保单快照）→ 定损 agent（LangChain4j AiServices + AssessTools）；工具 `parts.price` 按车型+配件名查 part_price 表，返回 priceCents + priceSourceId（证据引用），库中无此配件返回错误而非编造；产出维修清单+估价，写回 estimateCents；P0 识别置信度固定 0.9（如实标注，见 §10.5）；归档 ASSESS；
4. 任一 activity 失败（ActivityFailure/AgentUnavailableException）→ `toManual("模型/识别服务不可用，降级人工通道")`；
5. 可选：`POST /api/agent/fraud-report` 把分数+线索汇总成调查报告（只汇总，不打分不定性不处置）。

**实现**：ClaimWorkflowImpl:71-82、ClaimActivitiesImpl.fraudScore:117/assess:131、claim-core/fraud/FraudScorer.java、claim-agents/tools/AssessTools.java。
**生产差异**：评分器替换 XGBoost(JPMML)+NebulaGraph 图特征适配器（接口不变）；定损接入私有 VL 影像模型（不出域）+OCR 前置；置信度真实化并执行 <0.85 转人工。

### 11.5 分级路由（三通道 + 人工）

**设计方案**：路由是「风险×金额×置信度」的确定性门禁，阈值全部配置化（`claim.route.*`，生产接配置中心可调）。设计意图：小额低风险高置信全自动省人力；大额高风险强制人工调查守渗漏；中间地带自动理算+人工审批制衡。

**流程**（`route()`，判定顺序不可交换）：
1. `riskScore ≥ 70` **或** `估损 > ¥50,000` → **INVESTIGATE**（SIU 人工调查，agent 报告辅助）；
2. 否则 `估损 < ¥5,000` **且** `riskScore < 30` **且** `识别置信度 ≥ 0.85` → **FAST**（全自动，免人工审批）；
3. 其余 → **STANDARD**（自动理算 + 人工审批）；
4. 写回案件 channel，归档 ROUTE；状态 ROUTED；
5. INVESTIGATE/MANUAL → 转人工通道（§11.11）；FAST/STANDARD 继续理算；路由后若已收到异议 signal 同样升级人工。

**阈值默认值**（RouteProperties，单位分）：fastLimitCents=5000_00、standardLimitCents=50_000_00、riskLow=30、riskHigh=70、confidenceMin=0.85。

**实现**：ClaimActivitiesImpl.route:145、claim-workflow/RouteProperties.java。
**生产差异**：标准通道追加 10% 人工抽检（XXL-Job 抽样）；F7 复核质疑自动触发深查（P0 复核为按需 API）。

### 11.6 理算计算（纯代码计算器）

**设计方案**：金额是资金权的核心，**只允许纯代码计算**，LLM 禁入。公式固定、入参全部来自确定性数据源（定损额/保单快照/责任比例），每笔输出带可读 breakdown 供审计与前端展示。责任比例 P0 由描述关键词确定性映射（次要 30%/同等 50%/主要 70%/默认全责 100%）。

**流程**：
1. 经工具网关取保单快照与 `liability.percent`；
2. `ClaimCalculator.calculate()`：
   ```
   折旧 = 定损额 × 折旧率%
   责任后 = (定损额 − 折旧) × 责任比例%
   免赔后 = 责任后 − 免赔额
   应赔 = max(0, min(保单限额, 免赔后))
   ```
   入参校验：金额不得为负、比例须在 0–100（非法直接抛错，不静默兜底）；
3. 写回案件 payableCents，归档 CALCULATE（含 breakdown）；状态 CALCULATED。

**示例**：定损 ¥4,200、折旧 10%、全责、免赔 ¥500 → 4200−420=3780 → −500=3280 → 应赔 ¥3,280。

**实现**：claim-core/calc/ClaimCalculator.java、ClaimActivitiesImpl.calculate:163、ToolGatewayImpl.liabilityPercent。
**生产差异**：F6 LLM 理算草案与计算器双跑比对，差异落监控（机器版「人工推翻率」）；责任比例来自认定书结构化字段。

### 11.7 分级审批（额度授权）

**设计方案**：审批是人工制衡点，分级规则确定性：**快赔免人工（效率）、大额双人会签（制衡）、拒赔必确认（审慎）**。审批记录全量落库（操作人/结论/意见/金额），批准计数以库为准——重启/重试不丢审批进度。任何一票驳回即终止赔付。

**流程**：
1. `requiredApprovers(payableCents, channel)`：FAST → 0（直接支付）；应赔 > ¥100,000 → 2；其余 → 1；
2. need>0 → 状态 APPROVING，循环等待 APPROVE 决策（生产 signal / 本地轮询）：
   - 收到驳回（approved=false）→ `reject("审批驳回: 意见")` + 通知 REJECTED，流程终止；
   - 收到批准 → 计数+1（本地以 `approvalCount` 库计数为准，防重复计数）；
   - 期间客户异议 → 转 MANUAL；
3. 批准数达标 → 进入支付（§11.8）。

**实现**：claim-core/approval/ApprovalService.java:24、ClaimWorkflowImpl:100-118、LocalPipelineRunner:137-152。
**生产差异**：审批人身份走 RBAC+额度授权矩阵（P0 操作人为表单字段）；durable timer 超时自动升级上级审批（P0 本地 30 分钟窗口超时转人工关闭）。

### 11.8 支付与双账本（资金一致性）

**设计方案**：资金动作遵循「**幂等键先行、双账本同事务、事务消息保可见性、outbox 供回查、对账兜底**」。业务账（案件维度应付）与资金账（渠道维度实付）同库同事务落库，任何一笔赔付两账必然相等；重复支付请求返回原结果不重复记账。生产走 RocketMQ 半消息：账本落库成功消息才可见，进程崩溃由 broker 回查 outbox 补决策。

**流程**：
1. 状态 PAYING；幂等键 = `案件号:批次`（P0 批次恒 1）；
2. **生产（MQ 可用）**：`PaymentTransactionService.sendPayTransaction()` 发半消息 → 回调执行本地事务 `DoubleLedgerService.pay()` → COMMIT/ROLLBACK；异常返回 UNKNOW 交给回查；
3. **本地（无 MQ）**：直连 `DoubleLedgerService.pay()`；
4. `pay()` 内（@Transactional）：查 pay_outbox 幂等键——已存在 → 返回原金额原状态（alreadyProcessed）；不存在 → 同事务插入 ledger_business（PAYABLE）+ ledger_fund（OUT，渠道 BANK_DIRECT）+ pay_outbox（COMMITTED）；
5. broker 回查（网络断/进程崩）：`checkLocalTransaction` 查 outbox 状态 → COMMITTED 补 COMMIT / 无记录 ROLLBACK / 未知继续 UNKNOW；
6. 人工通道赔付金额以最终人工决策为准（写回 payableCents）；归档 PAY（金额+幂等键）。

**实现**：claim-core/ledger/DoubleLedgerService.java、claim-mq/PaymentTransactionService.java、ClaimActivitiesImpl.pay:182。
**生产差异**：真实持牌通道/银企直连划款 + 银行回执异步核对 + 长短款挂账 + 差错处理台（P0 为模拟支付，账本/幂等/事务消息机制完整）。

### 11.9 核心登记、通知与结案

**设计方案**：本系统不是记录系统——赔付生效后经**核心系统既有接口**登记（工具网关写工具，幂等），绝不绕过核心改保单。通知走事件发布（生产 RocketMQ claim-notify → 短信/推送；local 审计环回），agent 沟通只解释真实状态不承诺赔付。结案是状态机终点，SETTLED→CLOSED 两步留痕便于对账区分「已赔未归档」。

**流程**：
1. `registerCore`：工具网关 `core.register`——core_register 表已有记录 → 返回 `{registered:true, idempotent:true}`；否则插入并返回；归档 CORE_REGISTER；
2. 状态 SETTLED；`notify(SETTLED)`：发布通知事件 + 归档 NOTIFY；
3. 状态 CLOSED（终态）；
4. 客户后续进度咨询：`POST /api/agent/notify` 由沟通 agent 基于案件真实状态回答；未配 Key 返回 503，前端降级气泡提示以详情页状态为准。

**实现**：ClaimActivitiesImpl.registerCore:201/notify:207、ToolGatewayImpl.coreRegister、claim-agents/NotifyAgent.java。
**生产差异**：核心登记适配真实批单/赔付接口（mTLS）；通知接短信/推送网关；F8 争议情绪识别自动升级人工。

### 11.10 拒赔流程（人工确认强制）

**设计方案**：「纯模型结论不得作为拒赔依据」是合规红线。拒赔只有两个合法入口：**核保免责 + 人工确认**、**审批驳回**；两者都要求人的显式动作，且拒赔理由必须可引用（免责条款编号/审批意见）。

**流程**：
1. 核保免责 → 状态 APPROVING（step=REJECT_CONFIRM），等待人工确认；
2. 确认通过 → `reject(免责项编号串)`：状态 REJECTED + rejectReason 落库 + 归档 REJECT + 通知 REJECTED；
3. 确认不通过/异议 → 转 MANUAL 人工核赔（不自动拒赔）；
4. 审批驳回路径：任一驳回 → `reject("审批驳回: 意见")` + 通知，流程终止；
5. 人工通道关闭（金额≤0 或超时）→ `reject("人工处理关闭: …")`。

**实现**：ClaimWorkflowImpl:58-69/110-115/142、ClaimActivitiesImpl.reject:214。
**生产差异**：拒赔通知附条款原文解释（F3 生成，引用规则引擎命中项）；客户申诉进入异议流程（§11.11）。

### 11.11 人工通道、降级与异议升级

**设计方案**：人工通道是系统的「兜底态」而非异常态——**任何自动化失败都显式降级到 MANUAL 并归档原因，流程不停摆**。降级来源四类：模型/识别不可用、高风险大额路由、客户异议、管道异常。人工处理保留完整资金权：核赔师可填金额赔付（走同一支付/双账本链路），也可关闭拒赔。

**流程**：
1. 触发：`toManual(reason)` → channel=MANUAL、状态 MANUAL、归档 MANUAL 原因、通知 MANUAL；
2. 等待 MANUAL_HANDLE 决策（生产 signal / 本地轮询，30 分钟窗口）；
3. 批准且金额>0 → PAYING → pay(人工金额) → registerCore → SETTLED → CLOSED（与自动通道同一资金链路）；
4. 否则 → reject 关闭（REJECTED）；
5. **异议升级**：`POST /api/claims/{no}/dispute`——生产发 dispute signal，workflow 在**任何 await 节点**（补件/拒赔确认/审批/人工）收到即转 MANUAL；本地直接置 MANUAL；
6. 管道未捕获异常 → 状态 MANUAL + 错误日志（不静默丢案）。

**实现**：ClaimWorkflowImpl.toManual:128/awaitDecision:147、CaseService.dispute:102、LocalPipelineRunner.resumeManual:168。
**生产差异**：SIU 调查工作台独立视图（P0 与核赔工作台合一）；人工改判经 claim-feedback 回流标注平台。

### 11.12 对账（差错防线）

**设计方案**：对账是资金一致性的最后防线。P0 实现第一层：**业务账 vs 资金账**按案件+批次全量比对，缺账或金额不等落 recon_diff（OPEN）供差错处理；仪表盘实时显示未决差异数。设计上三层对账（§5.3）逐层兜底，任何一层发现差异都不自动改账，必须人工差错处理。

**流程**：
1. `POST /api/recon/run` → `ReconciliationService.reconcile()`：ledger_business LEFT JOIN ledger_fund（案件+批次），资金账缺失或金额不等 → 插入 recon_diff（OPEN）；
2. `GET /api/recon/diffs` 查询未决差异；仪表盘「待处理对账差」卡片展示；
3. 冒烟验收口径：全流程跑完对账**零差异**。

**实现**：claim-core/ledger/ReconciliationService.java。
**生产差异**：XXL-Job 日终调度；追加第二层（本系统 vs 核心系统）、第三层（账本 vs 银行回执）；长短款挂账与差错处理台。

### 11.13 证据哈希链与决策审计

**设计方案**：每步决策「事件 + 证据」双写：决策事件供查询/回放，证据快照以**哈希链**锚定防篡改——`digest = SHA-256(prevHash | outputJson)`，逐条挂链，首条锚 GENESIS。索引落库、对象字节落存储（生产 MinIO WORM≥10 年 / local 文件系统），校验时**从存储重读字节重算**，库被改或对象被改都会断链。

**流程**：
1. 每个 activity 步骤调 `archive(caseNo, step, outputJson)`：
   - 生成 DecisionEvent（traceId=UUID、actor、step、output、ts）→ EventPublisher（生产 RocketMQ 顺序消息按案件号分区 / local 日志环回 + claim_decision 表）；
   - EvidenceStore.snapshot：取案件最新 seq → digest=SHA256(prevHash|output) → 对象 `{caseNo}/{seq}.json` 写入存储 → evidence_index 落库（seq/evidenceId/objectKey/sha256/prevHash/step/ts）；
2. `GET /api/claims/{no}/evidence/verify` → verifyChain：按 seq 逐条重读对象字节、重算 digest、比对 sha256 与 prev_hash 衔接 → chainValid；
3. 工具调用另落 tool_audit（工具名/案件/入参/结果截断 2000 字符/ts），前端「工具审计」页签展示；
4. LLM 调用经 ChatModelListener 记 token 计量（prompt/completion），统计入仪表盘。

**实现**：claim-infra/minio/AbstractEvidenceStore.java（LocalFsEvidenceStore/MinioEvidenceStore 两实现）、ClaimActivitiesImpl.archive:228、claim-mq/LocalLogEventPublisher/RocketMqEventPublisher。
**生产差异**：MinIO 对象锁 WORM 合规保留≥10 年；inputHash 为真实输入 SHA-256（P0 为 output hashCode 十六进制，如实标注）；事件经数仓归档。

### 11.14 工具网关（案件级 ACL + 强制审计）

**设计方案**：agent 的 @Tool 只是 schema 语法糖，**方法体内强制委托工具网关**——白名单注册制（未注册工具直接 SecurityException）、必须携带案件上下文、每次调用全量审计。读写工具分域：复核 agent 只读，写工具仅 core.register（幂等）。进程内实现与生产独立网关**接口一致**，agent 侧代码零改动迁移。

**工具清单**：
| 工具 | 域 | 数据源/行为 |
|---|---|---|
| policy.query | 读 | policy_snapshot ⋈ claim_case，返回保单快照 JSON |
| parts.price | 读 | part_price 按车型+配件名，返回价格+来源 ID；无记录返回错误不编造 |
| history.count | 读 | 同保单历史已结案件数 |
| graph.flagged | 读 | repair_shop_flag 修理厂黑名单 |
| case.materials / case.description | 读 | 案件材料清单/描述 |
| liability.percent | 读 | 描述关键词 → 责任比例（30/50/70/100） |
| core.register | 写 | 核心登记，core_register 幂等 |

**实现**：claim-infra/jdbc/ToolGatewayImpl.java、claim-common/port/ToolGateway.java。
**生产差异**：独立网关服务（OAuth2 鉴权/限流/核心系统 mTLS 适配）；ToolProvider SPI 按 agent 类型动态授权（P0 白名单为全局静态）。

### 11.15 模型网关与 agent 装配

**设计方案**：模型调用一律经 LlmGatewayClient（JDK HttpClient 零 SDK，OpenAI 兼容协议），LangChain4j 侧由 GatewayChatModel/GatewayStreamingChatModel 双适配器收口——**禁止 OpenAiChatModel 直配 apiKey**。控制点全部在网关：agentType 路由、出域脱敏、按端点熔断、分类重试、usage 计量。agent 不可用是**一等公民异常**（AgentUnavailableException），上层显式降级而非静默吞掉。

**机制参数（已实现）**：
- 路由：按 agentType 查端点表（baseUrl+model 显式配置），支持备用端点故障切换；
- 脱敏：身份证（17 位+校验位）/银行卡（16–19 位）/手机号（1[3-9] 开头 11 位），先长后短防截断；私有化端点（影像不出域）跳过脱敏；
- 熔断：按端点连续失败 5 次 → 熔断 60s，到期 half-open 探测；
- 重试：429/5xx/网络超时分类退避重试（≤2 次）；4xx 参数鉴权错误与已出流的流式中断**不重试**；
- 超时：连接 5s、请求 60s；
- 计量：usage（prompt/completion tokens）落审计与统计。

**agent 装配（P0 六门面）**：AgentFactory 按类型 build AiServices；定损挂 AssessTools（价格库/保单）、复核挂 ReviewTools（只读）；CaseContext（ThreadLocal）把案件号注入工具调用，工具网关据此做案件级审计。六门面：intake 受理 / assess 定损 / coverage-explain 核保解释 / fraud-report 欺诈报告 / review 独立复核 / notify 客户沟通。

**实现**：claim-llm/LlmGatewayClient.java、MaskingService.java、GatewayChatModel/GatewayStreamingChatModel、claim-agents/AgentFacade.java、claim-agent-runtime/AgentFactory。
**生产差异**：语义缓存（Redis）、prompt 版本注入、内容安全过滤、成本落账到案件、八 agent 独立部署组（doc-extract/doc-vision 补齐，§6.2）。

### 11.16 重启恢复（local 管道 vs Temporal）

**设计方案**：生产恢复由 Temporal durable 执行天然保证（workflow 状态持久化，worker 重启自动续跑，无恢复代码）。local 管道线程不持久，恢复策略是**按案件当前状态续接等待节点**——只恢复「等人」的案件，不重跑已完成的步骤；中间态（COVERAGE_CHECK/ASSESSING 等）不自动续接而是告警，避免重复执行资金动作。

**流程（ApplicationReadyEvent）**：
1. 遍历案件（≤500）：
   - REPORTED / MATERIALS_PENDING → 重新提交管道（createCase 幂等，从头门禁检查）；
   - APPROVING → `resumeApproval`：读最近一条 COVERAGE 决策输出——`covered:false` → 续接拒赔确认节点；否则按 requiredApprovers 与库内已批准数续接赔付审批；
   - MANUAL → `resumeManual`：续接人工处理等待；
   - CLOSED/SETTLED/REJECTED → 终态跳过；
   - 其余中间态 → 告警不续接（日志明示「生产 Temporal 无此限制」）；
2. 等待节点统一 30 分钟窗口、2s 轮询；审批计数以 claim_approval 库为准，重启不丢已批准票。

**实现**：LocalPipelineRunner.recover:50/resumeApproval:181/resumeManual:168。
**实测**：重启后日志「本地管道已恢复 N 个未决案件的等待节点」，补件/审批动作续接有效。

### 11.17 前端工作台交互设计

**设计方案**：单文件 SPA（原生 JS，零构建依赖），hash 路由六视图：**仪表盘 / 报案登记 / 案件列表 / 人工工作台 / 审计追溯 / 风控批处理**；案件详情六页签：材料 / 决策时间线 / 人工审批 / 双账本 / 工具审计 / 客户通知。设计语言墨蓝×青碧，通道四色语义（FAST 琥珀/STANDARD 电蓝/INVESTIGATE 玫红/MANUAL 紫罗兰）。

**关键纪律**：
- **轮询纪律**：仪表盘 15s / 工作台 8s / 详情 3s；路由切换 stopPoll() 清定时器，输入框聚焦暂停刷新防打断；
- **降级可见**：AI 端点 503 → 明确降级提示（通知气泡/按钮提示），不假装成功；顶栏端点可用性灯带；
- **模态遮罩**：`.modal-scrim[hidden]{display:none}` 显式兜底（display:flex 会覆盖 UA 的 hidden 语义，曾致遮罩常驻挡屏）；Esc/点遮罩关闭；
- **缓存纪律**：local 静态资源强制 no-cache+must-revalidate，修复下发即生效，杜绝浏览器启发式缓存旧样式；
- **响应式**：桌面 + 375px 移动布局结构化验收通过。

**实现**：claim-gateway/src/main/resources/static/（index.html/app.js/style.css）。

### 11.18 欺诈关联图谱构建（T+1 批处理，as-built）

**目标**：把「案件—保单—修理厂」关联沉淀为可查询图谱，按确定性规则聚合修理厂风险并回写标记，供 `graph.flagged` 工具→欺诈评分（+30）→分级路由闭环消费。

**数据语义（两表分职）**：
- `graph_case_shop`：案件↔修理厂**关联登记**（报案/定损录入，source=INTAKE），只表达「这台车在这家厂修」；
- `repair_shop_flag`：**风险标记登记表**，source 二态——`MANUAL`（人工调查标记，永久保留）/ `GRAPH_BATCH`（批处理自动标记，重建即清）。`graph.flagged` 工具按 case_no 计数，语义不变。

**构建流程**（GraphBuildService.build，全量重建、源数据只读）：
1. 读 claim_case / policy_snapshot / graph_case_shop / repair_shop_flag；
2. 清空 graph_edge / graph_node，重插 CASE 节点（props: status/estimateCents/riskScore）、POLICY 节点、SHOP 节点，INSURED_BY / REPAIRED_AT 边（关联去重）；
3. 按厂聚合：关联案件数、高额案件数（估损 > ¥50,000，与欺诈评分大额档一致）、累计估损；
4. **标记规则**：同厂 ≥3 案 或 ≥2 笔高额案 → 该厂关联案件逐案回写 GRAPH_BATCH 标记；已有 MANUAL 标记的案件跳过（人工优先）；
5. 落 graph_build_run（触发方/节点/边/厂数/新增标记/耗时）。

**幂等与保留**：重建只清 GRAPH_BATCH 行与派生图表，MANUAL 标记永不丢失；重复构建结果一致（单测验证）。

**查询**：summary（最近运行+全厂风险表）、neighbourhood(caseNo)（自身+保单/修理厂一跳+同厂兄弟案，欺诈调查视角）、history（最近 20 次）。

**调度**：mysql/default 档位由 **XXL-Job** 接管——任务 `graphBuildJobHandler`（BEAN，CRON `0 30 2 * * ?`，FIRST 路由/串行阻塞/失败重试 1 次），执行器组 claim-pro-executor 自动注册（executor-ip=host.docker.internal 供容器回调宿主）；local 档位 Spring `@Scheduled` 兜底（`claim.batch.graph-cron`，条件装配防双调度）。启动跑一次（STARTUP）+ `POST /api/graph/build`（MANUAL）保留；runQuietly 失败只记日志不阻断主链路，XXL-Job 侧失败 handleFail 落调度日志。

**生产差异**：图存储同步 NebulaGraph、实时增量边、XXL-Job 分片广播多执行器；标记规则与回写语义不变。

**实现**：claim-gateway/.../batch/GraphBuildService.java、BatchScheduler.java、BatchJobHandlers.java、XxlJobConfig.java、BatchController.java；单测 GraphBuildServiceTest 4/4（建图/标记派生、幂等+人工保留、摘要风险、邻域兄弟案）。

### 11.19 金标回归（确定性核心重放，as-built）

**目标**：核赔师标注的期望裁决作为金标，任何规则/计算器/评分器/阈值改动导致裁决偏离即报红——发布门禁的 P0 形态。

**金标集**：`gold-cases.json` 15 案（GOLD-001~015），覆盖：三通道分级、三类法定免责（酒驾/无证/故意）+ 期间外/险种不符、责任比例（次要 30/同等 50/主要 70）、保单限额封顶、免赔额吞没、历史出险加分封顶、标记厂阻断快赔边界、大额双人审批。期望值全部手算并经单测验证。

**重放纪律**：纯代码逐案重放 核保→欺诈评分→分级→理算→审批数，**不触碰 LLM、不写业务表**；与生产管道共用单一事实源（DescriptionRules 责任/免责关键词、ChannelRouter 阈值纯函数、CoverageRuleEngine/FraudScorer/ClaimCalculator/ApprovalPolicy），杜绝「重放一套、生产一套」。

**比对**：逐字段比对期望裁决（Number 按 longValue 归一），漂移落 gold_run / gold_case_result（mismatches_json 含字段/期望/实际）。拒赔案只比对 covered/exclusions/payableCents=0。

**调度**：mysql/default 档位由 **XXL-Job** 接管——任务 `goldRegressionJobHandler`（BEAN，CRON `0 0 3 * * ?`，漂移 >0 案即 handleFail 报红调度日志，不盲目重试）；local 档位 Spring cron 兜底（`claim.batch.gold-cron`）。启动跑一次 + `POST /api/gold/run` 保留；查询 latest/history。前端「风控批处理」页展示通过率、逐案结果与漂移明细；仪表盘金标状态行。

**漂移检测验证**：单测篡改期望值（GOLD-001 金额/GOLD-002 通道）→ 精确报红 2 案；改路由阈值（fastLimitCents）→ 批量报红。

**生产差异**：金标库扩充 ≥2000 案、影子双跑 2–4 周（§9 发布流水线）；XXL-Job 日终调度已落地。

**实现**：claim-gateway/.../batch/GoldRegressionService.java、GoldCase.java、gold-cases.json；单测 GoldRegressionTest 3/3。

## 12. 验证结果（2026-11-18）

| 项 | 结果 |
|---|---|
| 单元测试 `./mvnw test` | 28/28（脱敏/模型网关/计算器/双账本幂等/审批分级/核保规则/描述规则 2/图谱构建 4/金标回归 3） |
| 端到端冒烟 `smoke-test.py` | 19/19（报案→补件→决策→结案→双账本→事件链→证据链→审计→对账零差异→统计→图谱构建/摘要/邻域→金标回归 15 案零漂移/逐案结果） |
| 浏览器闭环实测 | 报案→补件→审批/驳回→结案、异议升级、AI 503 降级、移动布局；风控批处理页渲染+立即构建/执行回归交互（mysql 档位） |
| 重启恢复 | local 管道等待节点续接实测通过 |
| MySQL 切库 | 14 表 H2→MySQL 迁移逐表行数校验全 OK（16 案/129 证据/133 决策），H2 文件只读保留作备份；20 表 DDL+种子；专用账号 claim；切库后冒烟 19/19、证据链校验通过 |
| 金标漂移检测 | 篡改期望值精确报红 2 案；改路由阈值批量报红（单测验证） |
| XXL-Job 调度 | 调度中心 2.4.1（Docker :8094，ops-pro 纳管）登录验证；执行器 claim-pro-executor :9999 自动注册心跳入库；两任务（02:30/03:00）手动触发 trigger/handle 均 200，graph_build_run/gold_run 落 trigger_by=XXL_JOB 行（金标 15/15）；local 档位 Spring cron 条件兜底防双调度 |
| 中间件 | ops-pro claim-shared 栈全 RUNNING（含 XXL-Job 调度中心）；MinIO 源码构建 + S3 建桶/传/读/删往返实测 |
| 事务消息回查演练 | 属生产档位验证项（§11 验证计划 3），local/mysql 无 MQ 不涉及 |

验证计划（生产档位）：compose 起基础设施 → 冒烟全链路 → kill 进程半消息回查补 COMMIT → Temporal 断点续跑 → 断模型端点纯人工降级演练 → 金标集回归。