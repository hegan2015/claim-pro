# claim-pro 操作手册（OPERATIONS）

版本：v1.0.0 ｜ 日期：2026-11-18 ｜ 适用：保险理赔多智能体工作台 + ops-pro 运维控制台 + 共享中间件栈
配套文档：[README](README.md)（上手）· [DESIGN](DESIGN.md)（架构）· [REQUIREMENTS](REQUIREMENTS.md)（需求）· [COLLABORATION](COLLABORATION.md)（协作决策）

---

## 1. 系统组成与访问地址

| 组件 | 地址 | 说明 |
|---|---|---|
| 理赔工作台 | http://127.0.0.1:8097/ | 报案/案件/审批/审计 SPA，本手册第 3 节 |
| 运维控制台 | http://127.0.0.1:8098/ | 应用启停/中间件/日志/审计，第 5 节 |
| Temporal UI | http://127.0.0.1:8088/ | 工作流实例可视化（生产编排时） |
| RocketMQ 控制台 | http://127.0.0.1:19876/ | Topic/消息查询 |
| MinIO 控制台 | http://127.0.0.1:9001/ | 证据影像对象存储 |
| MySQL | 127.0.0.1:3306 | 生产 profile 案件库（初始库 claim） |
| Redis | 127.0.0.1:6379 | 缓存/锁 |

> `127.0.0.1` 仅本机有效。同局域网其他设备用本机 IP，如 `http://192.168.31.85:8097/`（IP 以 `ipconfig getifaddr en0` 实测为准）。

**权力分层**（理解系统行为的前提）：AI agent 只做理解/查证/起草/沟通；资格判定、金额计算、审批生效、资金划转由确定性核心执行；流程走向由编排引擎（Temporal 或本地管道）控制。AI 不可用时业务不停摆，案件转人工通道。

## 2. 快速开始

```bash
cd claim-pro
./run.sh                    # 默认 mysql profile：MySQL claim 库 + 本地管道（需 claim-shared-mysql 运行中）
# 就绪后打开 http://127.0.0.1:8097/
python3 smoke-test.py       # 端到端冒烟 19 项（可选，验证全链路+批处理）
./stop.sh                   # 停止服务（不删数据）
```

- **零依赖开发**：`CLAIM_PROFILE=local ./run.sh`（H2 文件库，无需任何中间件）。
- 首次启动自动编译打包（约 1 分钟），之后秒起。
- mysql 档位前置：ops-pro 的 claim-shared-mysql 运行中；库表已初始化（`deploy/mysql/001_schema.sql` + `002_seed.sql`，见第 8 节）；账号口令在 `~/.config/claim-pro/claim.properties`。
- 启动日志出现「本地开发管道运行中，非生产编排」属正常告警——local/mysql 档位编排定位是功能验证/演示。
- 启动时图谱构建与金标回归各自动跑一次（STARTUP），日志可见「图谱构建完成」「金标回归完成」。
- 重启后未决案件自动续接等待节点（日志「本地管道已恢复 N 个未决案件的等待节点」）。
- **数据纪律**：`data/` 与 H2 库文件的删除/重置必须先经负责人明确确认；停服务可以，删数据不行。

## 3. 工作台操作指南

左侧导航五视图：**仪表盘 / 报案登记 / 案件列表 / 人工工作台 / 审计追溯**。顶栏含全局搜索、AI 端点可用性灯带、时钟。人工工作台角标 = 待处理案件数（审批中+人工+待补件）。

### 3.1 仪表盘

- 四张统计卡：案件总数、快赔通道占比、累计赔付金额（资金账本 OUT 方向合计）、待处理对账差。
- 通道分布环图 + 状态条形分布 + 最近案件流。数据 15 秒自动刷新。
- 「本地开发管道 / Temporal」引擎卡显示当前编排档位与证据存储实现。

### 3.2 报案登记

1. 填写：保单号、险种（默认 AUTO）、出险日期（默认今天）、事故类型（COLLISION/THEFT/FLOOD/FIRE）、事故描述。
2. 种子保单可用 `P-2026-0001` ~ `P-2026-0006`（见附录 B）；描述写清车型与受损部位可提升定损精度，例：「大众朗逸2023款追尾碰撞，前保险杠与左前大灯受损」。
3. 页面提供 4 个场景样本 chips，一键填充演示。
4. 「AI 结构化」按钮调用受理 agent 提取事实；未配 Key 时返回 503 并提示降级，**不影响报案提交**。
5. 提交后案件同步创建（API 返回即有案件号 `CLM-日期-XXXXXX`），管道异步推进：材料齐套检查 → 核保 → 反欺诈评分∥定损 → 分级路由 → 理算 → 审批 → 支付 → 结案。

### 3.3 案件列表

- 支持按案件号/保单号/描述搜索，按状态、通道过滤，点击表头排序。
- 状态字典（12 态）：

| 状态 | 含义 | 状态 | 含义 |
|---|---|---|---|
| REPORTED | 已报案 | CALCULATED | 已理算 |
| MATERIALS_PENDING | 待补件 | APPROVING | 审批中 |
| COVERAGE_CHECK | 核保中 | PAYING | 支付中 |
| ASSESSING | 定损中 | SETTLED | 已赔付 |
| ROUTED | 已分级 | REJECTED | 已拒赔 |
| MANUAL | 人工处理 | CLOSED | 已结案 |

- 通道字典（4 色）：FAST 快赔（琥珀）/ STANDARD 标准（电蓝）/ INVESTIGATE 调查（玫红）/ MANUAL 人工（紫罗兰）。

### 3.4 案件详情

概览区：状态/通道/估损/应赔/风险评分/识别置信度/时间戳；操作按钮：**证据链校验**（哈希链防篡改验证）、**AI 复核**（503 降级）、**异议升级**（弹窗填理由 → 案件转人工）。

六个页签：
- **材料**：已提交单证列表（类型/存储键/SHA-256/置信度）。
- **决策时间线**：全链路事件（REPORT/COVERAGE/FRAUD_SCORE/ASSESS/ROUTE/CALC/APPROVE/PAY/CORE_REGISTER/NOTIFY…），每步带输入输出 JSON、模型 token 计量。
- **人工审批**：审批记录（步骤/操作人/结论/意见/金额）。
- **双账本**：业务账本（事件驱动）与资金账本（OUT 赔付）逐笔对照。
- **工具审计**：agent 工具调用留痕（工具网关强制审计）。
- **客户通知**：与通知 agent 对话问案件进度；未配 Key 时显示降级气泡「通知模型端点不可用…案件进度请以详情页状态为准」。

详情页 3 秒轮询刷新（输入框聚焦时暂停，避免打断操作）。

### 3.5 人工工作台（核心操作位）

队列卡片按案件展示待办动作：

| 案件状态 | 可用操作 | 效果 |
|---|---|---|
| MATERIALS_PENDING | **一键补件（认定书 + 照片）** | 提交 ACCIDENT_REPORT + DAMAGE_PHOTO，管道立即续跑 |
| APPROVING（核保免责） | 拒赔确认 / 转人工 | 确认后 REJECTED；否则转人工 |
| APPROVING（赔付审批） | 通过 / 驳回 | 通过计数达标 → 支付结案；任一驳回 → REJECTED |
| MANUAL | 人工处理（填金额）/ 关闭 | 金额>0 → 按该金额赔付结案；否则拒赔关闭 |

**业务规则（确定性核心，页面只展示不篡改）**：
- 材料齐套 = 认定书（ACCIDENT_REPORT）+ 损伤照片（DAMAGE_PHOTO）两类齐全。
- 分级路由：估损 < ¥5,000 且风险分 < 30 且识别置信度 ≥ 0.85 → **FAST**；风险分 ≥ 70 或估损 > ¥50,000 → **INVESTIGATE**（SIU 人工调查）；其余 **STANDARD**。阈值可经 `claim.route.*` 配置调整。
- 审批分级：FAST → 0 人（自动赔付）；应赔 > ¥100,000 → 2 人会签；其余 1 人。
- 模型/识别服务异常 → 自动降级 MANUAL 通道并归档原因，不停摆。
- 支付幂等：重复请求返回原结果；赔付后双账本落库、核心系统登记、通知客户。

工作台 8 秒轮询；审批操作即时落库并 signal 编排引擎。

### 3.6 审计追溯

- 全局决策事件流（最近 200 条），按案件号/步骤过滤，点击展开输入输出 JSON。
- 证据哈希链：每步决策证据 SHA-256 链式锚定，详情页「证据链校验」一键验证 `chainValid`。
- 对账：`POST /api/recon/run` 触发双账本核对，差异进 `recon_diff` 表，仪表盘显示未决差异数。

### 3.7 风控批处理（图谱构建 / 金标回归）

- **四指标卡**：图谱节点/边数、标记修理厂数（人工+批处理）、金标通过率。
- **欺诈关联图谱**：T+1 全量重建「案件—保单—修理厂」图；标记规则=同厂 ≥3 案 或 ≥2 笔高额案（>¥5 万）；标记回写 `repair_shop_flag`（source=GRAPH_BATCH）→ `graph.flagged` 工具 → 欺诈评分 +30 → 路由闭环。人工标记（MANUAL）永久保留，重建只清批处理标记。「立即构建」手动触发；修理厂表含关联案件链接/高额案/累计估损/标记原因。
- **金标回归**：15 条核赔师标注案纯代码重放（核保→评分→分级→理算→审批数），不触碰 LLM/业务表；漂移即红，逐案明细含期望/实际。「执行回归」手动触发。
- **调度**：mysql/default 档位由 **XXL-Job 调度中心**（:8094，ops-pro 纳管）接管——图谱每日 02:30（graphBuildJobHandler）、金标每日 03:00（goldRegressionJobHandler），执行器 claim-pro-executor :9999 自动注册；local 零依赖档位 Spring cron 兜底（`claim.batch.graph-cron` / `claim.batch.gold-cron` 可配）。启动各跑一次 + 手动 API 保留；失败只记日志不阻断主链路。
- 仪表盘「金标回归 15/15 · 批处理中心 →」状态行直达本页。
- 生产差异：NebulaGraph 图存储 + 金标库 ≥2000 案（DESIGN §11.18/§11.19）；XXL-Job 日终调度已落地（运维见第 6 节）。

## 4. 服务运维（claim-pro）

```bash
./run.sh                                  # mysql 档位（默认）：MySQL claim 库 + 本地管道
CLAIM_PROFILE=local ./run.sh              # 零依赖档位：H2 文件库 + 本地管道
CLAIM_PROFILE=default ./run.sh            # 生产编排档位（需中间件就绪，见第 6/8 节）
CLAIM_PORT=8097 ./run.sh                  # 自定义端口（默认 8097）
./stop.sh                                 # 停止
curl -s http://127.0.0.1:8097/actuator/health    # 健康检查 {"status":"UP"}
curl -s http://127.0.0.1:8097/api/runtime        # {"orchestration":"LOCAL|TEMPORAL","evidenceStore":"..."}
tail -f logs/claim-pro.out                  # 实时日志
cat run/claim.pid                           # 进程 PID
```

- **重启恢复**：local/mysql 档位按案件状态续接等待节点（REPORTED/MATERIALS_PENDING 重跑管道；APPROVING 按最近核保结论区分赔付审批/拒赔确认；MANUAL 续接人工处理）；其余中间态告警不自动续接。生产档位由 Temporal durable 执行天然跨重启恢复，无此限制。
- 前端静态资源 local/mysql 档位从源码目录热加载（改 `claim-gateway/src/main/resources/static/` 刷新浏览器即生效，无需重启；浏览器须强制刷新加载新 JS）。
- 构建验证务必 `./mvnw test > /tmp/t.log 2>&1; echo EXIT=$?`——管道接 tail 会掩盖真实退出码。

## 5. ops-pro 运维控制台

```bash
cd ops-pro && ./run.sh      # 启动（8098）
./stop.sh                   # 停止（审计与状态保留）
```

六个页面：
- **总览**：应用/中间件运行比、Docker 环境状态、共享复用拓扑图、实时操作动态。
- **应用管理**：claim/cs/ride/payment/yiwu 五应用卡片，启动/停止按钮（按各仓库 run.sh/stop.sh，端口探测判活）。
- **共享中间件**：10 服务卡片（状态/端口/控制台链接/使用方），单个或全部启停。**停止不删数据**。
- **运维工具**：Adminer（:8089）/RocketMQ 控制台（:19876）/Dubbo Admin×2（:8086/:8087）/**XXL-Job 调度中心（:8094）**入口卡片。
- **日志中心**：选日志源（六应用）→ 选文件 → 查看尾部；SSE 实时尾随。
- **操作审计**：全部启停操作留痕，持久化 `data/state.json`（上限 500 条），失败带完整报错详情。

API 速查：

```
GET  /api/apps · /api/middleware · /api/docker-status · /api/tools · /api/audit
POST /api/apps/{name}/start|stop
POST /api/middleware/start-all|stop-all|{name}/start|stop
GET  /api/logs/sources · /api/logs?source=&file=&tail= · /api/logs/stream（SSE）
```

## 6. 中间件管理（Docker · claim-shared 栈）

**权威定义**：`ops-pro/deploy/middleware-compose.yml`（项目名 claim-shared，卷 claim-shared-*）。claim-pro 与 cs-pro **共用同一套** MySQL/RocketMQ/Redis，只此一份。claim-pro 内 `deploy/docker-compose.yml` 为同定义独立备用版。

前置：Docker Desktop 已安装并运行（状态栏鲸鱼图标 Running）。终端手动操作 docker 先：

```bash
export PATH="$HOME/.docker/bin:$PATH"
```

| 服务 | 端口 | 控制台 | 镜像 |
|---|---|---|---|
| temporal + ui | 7233 | :8088 | temporalio/auto-setup:1.24.2 + ui:2.26.2 |
| rocketmq-namesrv | 9876 | — | apache/rocketmq:5.1.4 |
| rocketmq-broker | 10911 | — | 同上（自动创建 Topic） |
| rocketmq-dashboard | 19876 | :19876 | apacherocketmq/rocketmq-dashboard:1.0.0 |
| mysql | 3306 | — | mysql:8（初始库 claim / xxl_job） |
| redis | 6379 | — | redis:7 |
| minio | 9000 | :9001 | minio/minio:RELEASE.2024-06-13T22-53-53Z（源码构建，见下） |
| zookeeper | 2181 | — | zookeeper（payment-pro Dubbo 注册） |
| zookeeper-ride | 2182 | — | zookeeper（ride-pro docker 模式 Dubbo 注册） |
| xxl-job-admin | 8094 | :8094/xxl-job-admin | xuxueli/xxl-job-admin:2.4.1（daocloud 拉取，见下） |

日常启停一律经 ops-pro 页面（或第 5 节 API），不手动 docker compose，避免项目名/卷不一致。

### MinIO 镜像重建（上游已停止社区版分发）

MinIO 官方 2025 起停止预编译分发，现成镜像全渠道拉不到。本仓库提供源码构建配方：

```bash
export PATH="$HOME/.docker/bin:$PATH"
cd ops-pro/deploy
docker build -f minio-from-source.Dockerfile -t minio/minio:RELEASE.2024-06-13T22-53-53Z .
# 构建完成后 ops-pro 页面点 minio「启动」
```

依赖通道：golang 基础镜像经 docker.m.daocloud.io、源码 github.com、Go 模块 goproxy.cn（均已实测可达）。验证：

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9000/minio/health/live   # 200
```

### XXL-Job 调度中心（claim-pro 风控批处理）

- **访问**：http://127.0.0.1:8094/xxl-job-admin（ops-pro「运维工具」页有入口）。账号 admin，口令在 `~/.config/ops-pro/ops.properties` 的 `xxljob.admin.password`（默认口令已改，勿用 123456）。
- **调度库**：共享 MySQL 内 `xxl_job` 库（8 表，官方 DDL 存档 `ops-pro/deploy/xxl-job/tables_xxl_job.sql`），专用账号 xxljob；口令 `xxljob.db.password`。
- **通信令牌**：admin 与执行器共用 accessToken，ops 侧 `xxljob.access.token`、claim 侧 `claim.xxljob.access-token`（`~/.config/claim-pro/claim.properties`），两处必须同值；compose 经 `XXL_JOB_DB_PASSWORD`/`XXL_JOB_ACCESS_TOKEN` 注入。
- **执行器**：claim-pro 内嵌（xxl-job-core 2.4.1），appname=claim-pro-executor，端口 9999，executor-ip=host.docker.internal（admin 容器经 Docker Desktop DNS 回调宿主）；mysql/default 档位自动装配，local 档位关闭（Spring cron 兜底，防双调度）。
- **任务**：执行器组「理赔风控批处理」（自动注册）下两条 BEAN 任务——欺诈关联图谱 T+1 构建（graphBuildJobHandler，CRON `0 30 2 * * ?`，失败重试 1 次）、金标 15 案回归（goldRegressionJobHandler，CRON `0 0 3 * * ?`，漂移即失败不重试）。FIRST 路由/串行阻塞/过期跳过。
- **手工触发验证**：管理台「任务管理→执行一次」，或 API（先登录取 Cookie）：

```bash
B=http://127.0.0.1:8094/xxl-job-admin
curl -s -c /tmp/xxl-cookie.txt -d 'userName=admin&password=<xxljob.admin.password>' $B/login
curl -s -b /tmp/xxl-cookie.txt -d 'id=2&executorParam=&addressList=' $B/jobinfo/trigger   # 图谱
curl -s -b /tmp/xxl-cookie.txt -d 'id=3&executorParam=&addressList=' $B/jobinfo/trigger   # 金标
# 成功标志：调度日志 trigger/handle 均 200；claim 库 graph_build_run/gold_run 出现 trigger_by=XXL_JOB 行
```

- 执行器心跳：`xxl_job_registry` 表应有 `claim-pro-executor → http://host.docker.internal:9999/`；执行日志落 claim-pro `logs/xxl-job/`（保留 30 天）。

### 数据卷与口令

- 卷：claim-shared-temporal/-temporal-db/-rocketmq-*/-mysql/-redis/-minio/-xxl-job-admin，停止/重启不丢数据；**删卷重置须负责人明确确认**。
- MySQL/MinIO/XXL-Job 口令：`~/.config/ops-pro/ops.properties`（目录 700/文件 600），仓库只留 `config/ops.properties.example`；compose 经环境变量注入。

## 7. AI 能力配置（可选）

不配 Key 系统完整可用：AI 端点 503 + 前端降级提示，案件走人工通道。配置后解锁报案结构化、AI 复核、客户通知、核保解释等：

```bash
mkdir -p ~/.config/claim-pro && chmod 700 ~/.config/claim-pro
cp claim-pro/config/claim.properties.example ~/.config/claim-pro/claim.properties
chmod 600 ~/.config/claim-pro/claim.properties
# 编辑填入 DeepSeek Key（sk-...），重启 claim-pro 生效
```

- 端点按 agent 类型路由（intake/notify/coverage-explain/assess/review/fraud-report），可各配各的 Key/模型；fallback 兜底。
- **官方模型 ID ≠ 第三方平台 ID**（如 deepseek-flash 官方路由名与第三方 `deepseek-v4.1-flash-1017` 不同），抄错会 404。
- 模型网关强制：脱敏前置、按端点熔断（连续失败熔 60s）、分类退避重试、token 计量入审计。
- 密钥纪律：真实 Key 一律仓库外，gitignore 不算安全；Key 不写入文档/记忆/提交。

## 8. 切换生产编排档位

```bash
# 1. ops-pro 页面「全部启动」中间件（含 XXL-Job 调度中心）
# 2. 配置生产口令（MySQL/MinIO，见第 6 节）
# 3. 重启 claim-pro 到生产档位
cd claim-pro && ./stop.sh
CLAIM_PROFILE=default ./run.sh
curl -s http://127.0.0.1:8097/api/runtime    # orchestration 应为 TEMPORAL
```

- 生产档位：Temporal 编排（案件=workflow 实例，workflowId=案件号）、MySQL 案件库、RocketMQ 事件+支付事务消息、MinIO 证据存储。
- Temporal 未就绪时应用仍可启动，报案接口返回 503 并提示启动中间件（不做静默 mock）。
- local 的 H2 数据与 mysql/default 的 MySQL claim 库是两套库；**mysql 与 default 共用 claim 库**，切换不丢数据。

### 8.1 H2 → MySQL 一次性迁移（已执行，2026-11-18）

```bash
./stop.sh                                        # 1. 停应用（H2 文件释放写锁）
# 2. 建表（20 表，幂等 IF NOT EXISTS）
docker exec -i claim-shared-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 claim < deploy/mysql/001_schema.sql
# 3. 迁移（H2 只读 ACCESS_MODE_DATA=r，逐表行数校验，失败回滚；口令走环境变量不回显）
MIGRATE_MYSQL_PASSWORD='<claim 账号口令>' java -cp <h2.jar>:<mysql-connector-j.jar> \
  deploy/mysql/MigrateH2ToMysql.java ./data/claim \
  'jdbc:mysql://127.0.0.1:3306/claim?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8' claim
# 4. 种子补齐（INSERT IGNORE 幂等；务必 --default-character-set=utf8mb4 防中文双重编码）
docker exec -i claim-shared-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 claim < deploy/mysql/002_seed.sql
./run.sh                                         # 5. mysql profile 启动
```

- 迁移覆盖 14 张业务表（案件/材料/审批/双账本/outbox/证据索引/决策/审计/核心登记等）；派生图表（graph_*）与金标表由启动批处理重建，不迁移。
- H2 快照无 `graph_case_shop` 表时工具自动 SKIP，由种子补齐。
- **H2 文件原样保留作备份**（data/claim.mv.db），删除须先经负责人确认。
- 实测：16 案/129 证据/133 决策全量迁移行数校验 OK，切库后冒烟 19/19。

## 9. 验证与测试

```bash
cd claim-pro
./mvnw test > /tmp/t.log 2>&1; echo EXIT=$?     # 单测 28 项（脱敏/网关/计算器/双账本审批/核保/描述规则/图谱/金标）
python3 smoke-test.py                            # 冒烟 19 项，需服务已启动
python3 smoke-test.py http://127.0.0.1:8097      # 显式指定地址
```

冒烟覆盖：健康→运行时→报案建案→补件→管道推进→人工决策→结案→赔付金额→双账本→事件链→证据哈希链→审计→对账零差异→统计→图谱构建/摘要/邻域→金标回归零漂移/逐案结果。

批处理手工验证：

```bash
curl -s -X POST http://127.0.0.1:8097/api/graph/build     # 立即构建（返回节点/边/新标记数）
curl -s http://127.0.0.1:8097/api/graph/summary           # 修理厂风险表
curl -s http://127.0.0.1:8097/api/graph/case/<案件号>      # 案件邻域（同厂兄弟案）
curl -s -X POST http://127.0.0.1:8097/api/gold/run        # 执行回归（15/15 为零漂移）
curl -s http://127.0.0.1:8097/api/gold/latest             # 最近运行+逐案结果
```

XXL-Job 调度验证（mysql 档位）：管理台「执行一次」两任务（见第 6 节 XXL-Job 小节），调度日志 trigger/handle 均 200，claim 库出现 trigger_by=XXL_JOB 行即通过；执行器心跳查 xxl_job_registry。

手工 E2E 路径（浏览器）：报案登记填样本 → 提交 → 案件列表找到新案 → 详情「一键补件」→ 工作台审批通过 → 详情确认 CLOSED + 双账本 + 时间线完整。

## 10. 故障排查

| 现象 | 排查与处置 |
|---|---|
| 工作台打不开 | ① `curl http://127.0.0.1:8097/actuator/health` 无响应 → `./run.sh`；② 健康但页面空白 → 浏览器页签问题，刷新或重开；③ 其他设备访问 → 用局域网 IP 而非 127.0.0.1 |
| 页面模糊一层点不动 | 历史缺陷（模态遮罩 hidden 被 display:flex 覆盖）已修复，且 local 档位静态资源已强制 `Cache-Control: no-cache, must-revalidate` 防浏览器缓存旧样式；若仍复现先强刷（Ctrl/Cmd+Shift+R），再查 style.css `.modal-scrim[hidden]` 规则是否在位 |
| 报案 503 | 生产档位 Temporal 未就绪 → ops-pro 启动 temporal；或临时切 local 档位 |
| AI 按钮 503 | 未配 Key（正常降级）或端点熔断中（等 60s 自动恢复）；查 `~/.config/claim-pro/claim.properties` |
| 案件卡中间态不动 | local 档位重启后仅续接四类等待态；ASSESSING 等中间态需人工介入（详情页异议升级转人工）；生产档位 Temporal 自动续跑 |
| 补件后不推进 | 确认两类材料齐全（认定书+照片）；local 管道 2s 轮询，稍候；查 logs/claim-pro.out |
| minio 起不来 | 镜像缺失 → 第 6 节源码构建；报 docker-credential-desktop → ops-pro 已修 composeEnv PATH，确认用的是修复后版本（f832a3e+） |
| docker 命令找不到 | `export PATH="$HOME/.docker/bin:$PATH"`；守护进程未运行 → 启动 Docker Desktop |
| 端口被占 | 附录 A 端口总表核对；`lsof -nP -iTCP:8097 -sTCP:LISTEN` 找占用进程 |
| 编译报 Lombok TypeTag | 本机 JDK 26 需 Lombok 1.18.48（pom 已覆盖 lombok.version），勿回退 |
| 构建"成功"但测试没跑 | 管道 tail 掩盖退出码，改 `> log 2>&1; echo EXIT=$?` 复核 |

## 11. 安全与数据纪律

1. 密钥一律仓库外 `~/.config/<app>/`（700/600），仓库只留 example。
2. data/、H2 库、Docker 数据卷的删除/重置必须先经负责人明确确认。
3. git 提交/推送听指令；不提交 data/logs/密钥；不 force push。
4. 共享浏览器：自动化不劫持用户页签，借用须授权并还原。
5. 多会话并行同改工作区：动手前先查文件现状。

## 附录 A：端口总表（六系统错开可同跑）

| 系统 | 端口 | 系统 | 端口 |
|---|---|---|---|
| claim-pro | 8097 | ride-pro | 8090/2182/8093/2090x/9101 |
| ops-pro | 8098 | payment-pro | 8080 系 |
| cs-pro | 8096（双开 8099） | yiwu-pro | 8095 |

中间件：3306/6379/7233/8088/9876/10911/19876/9000/9001/2181/2182/**8094（XXL-Job admin）**；claim-pro 执行器 **9999**。

## 附录 B：种子保单（local 档位 data.sql / mysql 档位 deploy/mysql/002_seed.sql，幂等）

| 保单号 | 有效期 | 承保类型 | 限额 | 免赔额 | 折旧率 |
|---|---|---|---|---|---|
| P-2026-0001 | 2026-01-01~12-31 | COLLISION,THEFT,FLOOD,FIRE | ¥2,000,000 | ¥500 | 10% |
| P-2026-0002 | 2026-01-01~12-31 | 同上 | ¥2,000,000 | ¥500 | 10% |
| P-2026-0003 | 2026-02-01~2027-01-31 | COLLISION,THEFT | ¥3,000,000 | ¥1,000 | 15% |
| P-2026-0004 | 2026-03-01~2027-02-28 | COLLISION,THEFT,FLOOD | ¥2,000,000 | ¥500 | 10% |
| P-2026-0005 | 2026-01-01~12-31 | 全四类 | ¥2,000,000 | ¥500 | 10% |
| P-2026-0006 | 2026-05-01~2027-04-30 | 全四类 | ¥2,500,000 | ¥800 | 12% |

## 附录 C：核心 API 速查

```
POST /api/claims                          报案（同步建案，返回案件行）
GET  /api/claims?status=&channel=&limit=  案件列表
GET  /api/claims/{no}                     详情（案件/材料/审批/事件/双账本）
POST /api/claims/{no}/materials           补件（数组：type/storageKey/confidence）
POST /api/claims/{no}/decision            人工决策（step/approved/operator/comment/amountCents）
POST /api/claims/{no}/dispute             异议升级（reason）
GET  /api/claims/{no}/evidence/verify     证据哈希链校验
GET  /api/claims/{no}/tool-audit          工具调用审计
POST /api/recon/run · GET /api/recon/diffs  对账
POST /api/graph/build                     图谱立即构建（MANUAL）
GET  /api/graph/summary                   图谱摘要（最近运行+修理厂风险表）
GET  /api/graph/case/{no}                 案件邻域（一跳+同厂兄弟案）
GET  /api/graph/history                   构建历史（最近 20）
POST /api/gold/run                        金标回归执行（MANUAL）
GET  /api/gold/latest                     最近运行+逐案结果
GET  /api/gold/history                    运行历史
GET  /api/stats · /api/audit/recent       统计 / 审计事件
GET  /api/runtime · /actuator/health      运行时档位 / 健康
POST /api/agent/intake|notify|review/{no} AI 能力（503=降级）
GET  /api/agent/availability              六端点可用性
```