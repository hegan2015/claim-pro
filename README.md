# claim-pro · 保险理赔多智能体系统（商用级原型）

保险公司车险理赔多智能体工作台：**agent 承担理解/查证/起草/沟通，资格判定、金额、审批与资金归确定性核心，流程走向归 Temporal**。Java 21 + Spring Boot 3.2 + LangChain4j 1.0.1 + Temporal + RocketMQ，Maven 十模块，配套理赔工作台 SPA 与运维控制台 ops-pro。

> 新会话/新成员请先读 [COLLABORATION.md](COLLABORATION.md)（用户指导要点与已拍板决策），再读 [REQUIREMENTS.md](REQUIREMENTS.md) 与 [DESIGN.md](DESIGN.md)。

## 快速开始

```bash
./run.sh          # 默认 local profile：H2 + 本地管道，零中间件可跑
./stop.sh         # 停止（不删数据）
python3 smoke-test.py   # 端到端冒烟 14 项（零依赖，需服务已启动）
./mvnw test       # 单测 17 项
```

- 工作台：http://127.0.0.1:8097/ （报案/案件库/审批工作台/审计/仪表盘）
- 健康：`/actuator/health` ｜ 运行时：`/api/runtime`（TEMPORAL 或 LOCAL）

### 两种运行档位

| 档位 | 启动 | 编排 | 存储 | 适用 |
|---|---|---|---|---|
| local（默认） | `./run.sh` | LocalPipelineRunner 同构状态机（启动即告警非生产），重启自动续接等待节点 | H2 + 本地文件系统证据库 + 审计环回 | 无中间件的功能验证/演示 |
| 生产编排 | `CLAIM_PROFILE=default ./run.sh` | Temporal durable 工作流（跨重启恢复） | MySQL + MinIO + RocketMQ | 中间件由 ops-pro 启动后 |

中间件统一由 **ops-pro（:8098）** 的共享 compose 栈管理（Temporal/RocketMQ/MySQL/Redis/MinIO，与 cs-pro 复用同一套），本仓库 `deploy/docker-compose.yml` 为同定义独立备用版。

### AI 能力（可选）

不配 Key 也能完整跑流程：AI 端点返回 503，前端优雅降级，案件走人工通道。配置后解锁报案结构化、复核意见、客户通知等：

```bash
mkdir -p ~/.config/claim-pro && chmod 700 ~/.config/claim-pro
cp config/claim.properties.example ~/.config/claim-pro/claim.properties
chmod 600 ~/.config/claim-pro/claim.properties   # 填入 DeepSeek Key
```

模型调用全部经模型网关（路由/脱敏/熔断/重试/计量），LangChain4j 侧由自定义 `GatewayChatModel`/`GatewayStreamingChatModel` 收口——**禁止 OpenAiChatModel 直配 apiKey**。

## 模块结构

| 模块 | 职责 |
|---|---|
| claim-common | 领域模型与端口（CaseRepository/EvidenceStore 等） |
| claim-llm | 模型网关客户端 + LangChain4j 双适配器 + 脱敏 |
| claim-agent-runtime | AiServices 装配工厂（AgentFactory） |
| claim-agents | 六类 agent 门面（intake/assess/coverage-explain/fraud-report/review/notify） |
| claim-core | 确定性核心：核保规则引擎/理算计算器/分级审批/双账本/对账（LLM 禁入） |
| claim-mq | RocketMQ 事件发布 + local 审计环回 |
| claim-workflow | Temporal 工作流/activity + 本地开发管道（含重启恢复） |
| claim-infra | JDBC 仓储/证据存储（本地 FS 与 MinIO） |
| claim-gateway | REST API + 理赔工作台 SPA（static/） |

## 核心 API

```
POST /api/claims                        报案（同步建案）
POST /api/claims/{no}/materials         补件（ACCIDENT_REPORT + DAMAGE_PHOTO 齐套推进）
POST /api/claims/{no}/decision          人工决策（APPROVE / REJECT_CONFIRM / MANUAL_HANDLE）
POST /api/claims/{no}/dispute           异议升级人工
GET  /api/claims/{no}                   详情（案件/材料/审批/事件/双账本）
GET  /api/claims/{no}/evidence/verify   证据哈希链校验
POST /api/recon/run                     双账本对账
GET  /api/stats · /api/audit/recent     仪表盘统计 / 审计事件
```

## 验证状态（2026-11-18）

单测 17/17 ｜ 冒烟 14/14 ｜ 浏览器闭环实测（报案→补件→审批/驳回→结案、异议升级、AI 降级、移动布局）｜ 重启恢复实测。P0 与目标架构的差异见 [DESIGN.md §10.5](DESIGN.md)（单队列、规则评分器、模拟支付等，如实标注）。

## 约束

- 端口 8097（与 ride 8090 系/payment 8080 系/yiwu 8095/cs 8096/ops 8098 错开，可同跑）。
- 密钥一律仓库外 `~/.config/claim-pro/`（700/600），仓库只留 example。
- data/ 与 H2 库的删除/重置必须先经用户明确确认；停服务可以，删数据不行。