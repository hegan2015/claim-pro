# claim-pro 设计文档（DESIGN）

版本：v1.0.0 ｜ 日期：2026-11-18 ｜ 状态：定稿（与 REQUIREMENTS.md v1.0.0 配套）
口径：**商用生产架构**（非本地内置版）；消息层 **RocketMQ**（替代 Kafka）；编排 Temporal；agent 框架 LangChain4j；主力模型 DeepSeek。

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
| 图谱 | NebulaGraph | T+1 批量+实时增量 |
| 证据 | MinIO/云 OSS + WORM 合规保留 | 哈希链防篡改 |
| 规则 | Drools（条款规则包，带生效日期） | 核赔师可参与维护 |
| 可观测 | OTel + SkyWalking + Prometheus/Grafana + Loki | trace_id 贯通 workflow→agent→工具→模型 |
| 批处理 | XXL-Job | 日终对账/图谱构建/金标回归 |
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
├── REQUIREMENTS.md / DESIGN.md
├── claim-common          # 案件模型/幂等键/审计事件/端口接口
├── claim-llm             # 模型网关客户端 + GatewayChatModel + 路由/脱敏/计量
├── claim-agent-runtime   # AiServices 装配/工具注册/Guardrails/记忆
├── claim-agents          # 8 agent 接口与 @Tool 工具集
├── claim-core            # 规则引擎/理算计算器/审批/双账本（LLM 禁入）
├── claim-workflow        # Temporal workflow/activity 定义与 worker
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

### 10.4 本机开发约束（如实标注）

本机已装 Docker Desktop：中间件统一由 ops-pro（8098）的 claim-shared compose 栈管理（Temporal/RocketMQ/MySQL/Redis/MinIO），claim-pro 与 cs-pro 共用。应用编译用 mvnw；无中间件时以 `CLAIM_PROFILE=local` 启动：H2 + 本地文件系统证据库 + LocalLogEventPublisher（审计环回）+ LocalPipelineRunner（本地开发管道），启动即告警非生产编排。DeepSeek 联调需 `~/.config/claim-pro/llm.properties` 配置 Key，未配置时 AI 端点 503、流程走人工降级通道。

### 10.5 P0 实现简化清单（与目标架构的差异，如实标注）

- **单 Task Queue**：P0 所有 activity 注册在 `claim-main` 单队列，4.1 的六队列物理隔离属 P1 部署拆分；
- **本地开发管道**：`claim.temporal.enabled=false` 时由 LocalPipelineRunner 以同构状态机跑流程，人工节点轮询 claim_approval 表代替 signal；重启后经 ApplicationReadyEvent 按案件状态续接等待节点（REPORTED/MATERIALS_PENDING/APPROVING/MANUAL），其余中间态告警不自动续接——生产一律 Temporal durable 恢复，无此限制；
- **欺诈评分**：P0 为确定性规则评分器（金额/历史/材料一致性加权），非模型推理；反欺诈 agent 仅做报告汇总；
- **报案结构化**：P0 为关键词事实提取 + LLM 可选增强，LLM 不可用时降级关键词结果；
- **支付**：P0 为模拟支付 activity + 双账本落库，真实渠道对接属 P1；
- **条款检索**：P0 无向量库，核保走确定性规则引擎（免责条款表）。

## 11. 验证计划

1. `./mvnw verify`：全模块编译+单测（规则引擎/计算器/双账本/幂等/路由/脱敏）；
2. compose 起基础设施 → run.sh 启动 → 冒烟：报案→补件→核保→定损→理算→审批→支付模拟→结案；
3. 事务消息回查演练（kill 进程后半消息回查补 COMMIT）；
4. 重启断点恢复（workflow 续跑）；证据哈希链校验；
5. 降级演练（断模型端点 → 纯人工通道）；
6. 金标集回归脚本（llm-eval 模式升级）。