# claim-pro · 保险理赔多智能体系统（商用级原型）

保险公司车险理赔多智能体工作台：**agent 承担理解/查证/起草/沟通，资格判定、金额、审批与资金归确定性核心，流程走向归 Temporal**。Java 21 + Spring Boot 3.2 + LangChain4j 1.0.1 + Temporal + RocketMQ，Maven 十模块，配套理赔工作台 SPA 与运维控制台 ops-pro。

> 新会话/新成员请先读 [COLLABORATION.md](COLLABORATION.md)（用户指导要点与已拍板决策）。

### 文档地图

| 文档 | 职责 | 适合谁 |
|---|---|---|
| [REQUIREMENTS.md](REQUIREMENTS.md) v1.1.0 | 业务目标/8 agent 职责与权力边界/流程分级/资金与非功能需求；**§4.1 需求→实现映射、§9 P0 验收记录** | 产品/核赔/验收 |
| [DESIGN.md](DESIGN.md) v1.1.0 | 目标商用架构（§1–9）+ 工程部署（§10）+ **§11 逐业务流程设计详解（as-built：报案/补件/核保/反欺诈∥定损/路由/理算/审批/支付双账本/拒赔/人工降级/对账/证据链/双网关/重启恢复/前端）** + §12 验证结果 | 架构/开发 |
| [OPERATIONS.md](OPERATIONS.md) | 操作手册：工作台操作/业务规则字典/启停运维/中间件与 MinIO 源码重建/AI 配置/故障排查 | 使用/运维 |
| [COLLABORATION.md](COLLABORATION.md) | 用户指导时间线/已拍板决策/踩坑教训/协作原则 | 新会话/新成员 |

## 快速开始

```bash
./run.sh          # 默认 mysql profile：MySQL claim 库 + 本地管道 + XXL-Job 执行器（需 claim-shared-mysql / xxl-job-admin）
CLAIM_PROFILE=local ./run.sh   # 零依赖档位：H2 + 本地管道，无中间件可跑
./stop.sh         # 停止（不删数据）
python3 smoke-test.py   # 端到端冒烟 19 项（零依赖，需服务已启动）
./mvnw test       # 单测 28 项
```

- 工作台：http://127.0.0.1:8097/ （报案/案件库/审批工作台/审计/仪表盘/风控批处理）
- 健康：`/actuator/health` ｜ 运行时：`/api/runtime`（TEMPORAL 或 LOCAL）

### 三种运行档位

| 档位 | 启动 | 编排 | 存储 | 批处理调度 | 适用 |
|---|---|---|---|---|---|
| local | `CLAIM_PROFILE=local ./run.sh` | LocalPipelineRunner 同构状态机（启动即告警非生产），重启自动续接等待节点 | H2 + 本地文件系统证据库 + 审计环回 | Spring cron 兜底（零依赖） | 无中间件的功能验证/演示 |
| mysql（默认） | `./run.sh` | 同上 | **MySQL claim 库**（deploy/mysql DDL+种子）+ 本地证据/事件件 | **XXL-Job**（调度中心 :8094，执行器 :9999） | 有共享 MySQL 的日常开发/演示 |
| 生产编排 | `CLAIM_PROFILE=default ./run.sh` | Temporal durable 工作流（跨重启恢复） | MySQL + MinIO + RocketMQ | **XXL-Job** | 中间件由 ops-pro 启动后 |

H2→MySQL 迁移工具 `deploy/mysql/MigrateH2ToMysql.java`（只读 H2、行数校验、失败回滚），用法见 [OPERATIONS.md §8.1](OPERATIONS.md)。

中间件统一由 **ops-pro（:8098）** 的共享 compose 栈管理（Temporal/RocketMQ/MySQL/Redis/MinIO/ZooKeeper×2/XXL-Job 调度中心，与 cs-pro 复用同一套），本仓库 `deploy/docker-compose.yml` 为同定义独立备用版。图谱构建（每日 02:30）/金标回归（每日 03:00）由 XXL-Job 调度，管理台入口在 ops-pro「运维工具」页，运维详见 [OPERATIONS.md §6](OPERATIONS.md)。

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
POST /api/claims/{no}/materials         补件元数据登记（ACCIDENT_REPORT + DAMAGE_PHOTO 齐套推进）
POST /api/claims/{no}/materials/upload  真实文件上传（multipart：file/type/confidence；jpg/jpeg/png/gif/webp/pdf ≤20MB）
GET  /api/claims/{no}/materials/{mid}/content   材料文件查看（读回复核 SHA-256；仅登记材料返回 404）
POST /api/claims/{no}/decision          人工决策（APPROVE / REJECT_CONFIRM / MANUAL_HANDLE）
POST /api/claims/{no}/dispute           异议升级人工
GET  /api/claims/{no}                   详情（案件/材料/审批/事件/双账本）
GET  /api/claims/{no}/evidence/verify   证据哈希链校验
POST /api/recon/run                     双账本对账
POST /api/graph/build · GET /api/graph/summary|case/{no}|history   欺诈关联图谱构建/查询
POST /api/gold/run · GET /api/gold/latest|history                  金标回归执行/查询
GET  /api/stats · /api/audit/recent     仪表盘统计 / 审计事件
```

## 验证状态（2026-11-18）

单测 28/28 ｜ 冒烟 24/24（含材料文件上传/读回/仅登记拒绝 + 图谱构建/邻域 + 金标 15 案零漂移）｜ 浏览器闭环实测（报案→补件→审批/驳回→结案、异议升级、AI 降级、移动布局、风控批处理页交互、材料文件上传→缩略图→查看）｜ 材料存储双形态实测（本地磁盘 + MinIO 各 24/24）｜ 重启恢复实测 ｜ 对账零差异 ｜ H2→MySQL 14 表迁移行数校验全 OK（H2 备份保留）。
逐流程实现详解见 [DESIGN.md §11](DESIGN.md)，P0 与目标架构差异见 [DESIGN.md §10.5](DESIGN.md)（单队列、规则评分器、模拟支付等，如实标注）；需求逐条映射与验收证据见 [REQUIREMENTS.md §4.1/§9](REQUIREMENTS.md)。

## 约束

- 端口 8097（与 ride 8090 系/payment 8080 系/yiwu 8095/cs 8096/ops 8098 错开，可同跑）。
- 密钥一律仓库外 `~/.config/claim-pro/`（700/600），仓库只留 example。
- data/ 与 H2 库的删除/重置必须先经用户明确确认；停服务可以，删数据不行。