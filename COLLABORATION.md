# claim-pro 协作指南 —— 用户指导 AI 开发要点复盘

> 整理日期：2026-11-18 ｜ 来源：本项目立项到交付的真实协作记录复盘
> 用途：新会话开工前读本文件，快速对齐用户的工作方式与本项目已拍板的决策，避免重复试探和回退。
> 配套：REQUIREMENTS.md（需求定稿）、DESIGN.md（设计定稿，10.5 节为 P0 实现简化清单）、README.md（上手）。

## 一、项目定位与硬约束

- **保险公司车险理赔多智能体系统，商用级**（非 demo、非本地内置玩具版）。端口 **8097**。
- **权力分层**（用户认可的架构基石）：agent 只做理解/查证/起草/沟通；资格判定、金额、审批生效、资金划转归确定性核心；流程走向归 Temporal。LLM 的不确定性只允许存在于节点内部。
- **数据神圣**：停服务可以，删除/重置 data/ 与 H2 库必须先经用户明确确认。
- **密钥出仓库**：真实配置放 `~/.config/claim-pro/claim.properties`（目录 700/文件 600），仓库只留 `config/claim.properties.example`；gitignore 不算安全。
- **中间件 Docker 统一管理、与 cs-pro 复用**：权威定义在 ops-pro `deploy/middleware-compose.yml`（项目名 claim-shared，卷 claim-shared-*），不在本仓库私起一套。

## 二、用户指导时间线（原话 → 落地）

| 用户指导 | 落地结果 |
|---|---|
| 「按照商用的设计，不是 demo」 | REQUIREMENTS/DESIGN 按生产架构定稿：K8s 部署、双活、WORM 证据≥10 年、金标回归 |
| 「agent 框架按照企业正式的做」 | LangChain4j 1.0.1 正式扩展位（AiServices/@Tool），控制点收口在自定义 GatewayChatModel/GatewayStreamingChatModel + 模型网关；**禁止 OpenAiChatModel 直配 apiKey 的 demo 用法** |
| 技术选型对齐 cs-pro 已验证模式 | 模型网关客户端移植 cs-llm：JDK HttpClient 零 SDK、OpenAI 兼容、按端点熔断、分类退避重试 |
| 「按照商用的搭建，不用本地内置，kafaka 换成 rockmq，把需求文档与设计文档先保留起来，然后开始干活」 | 消息层定 RocketMQ 5.x（事件流+支付事务消息）；先落盘 REQUIREMENTS.md/DESIGN.md 再动代码 |
| 「另外这次把前端体验也做好」 | 理赔工作台 SPA 纳入交付（hash 路由 6 视图、墨蓝×青碧设计语言、四通道色、移动适配），体验优先而非附赠 |
| 「方便运维你还要给我做一套运维系统，方便启动中间件与应用与查看日志，中间件与智能客服要复用」 | 独立 ops-pro（8098）：应用启停/共享中间件/日志中心/操作审计；claim 与 cs 共用同一套 MySQL/RocketMQ |
| 「中间件都用 docker 管理」 | compose 统一定义，ops-pro 页面化启停，停止不删数据 |
| 「dock desktop 是做什么现在要安装 是必须得吗」→「装」×2 | 先通俗解释 Docker Desktop 作用与必要性，用户拍板后安装（4.99.0），中间件栈 6/7 拉起 |
| 「理赔照片为啥查看不了」→「都一会改了」 | 查明 P0 材料仅元数据登记无文件本体；用户拍板全量改造：真实文件上传（MinIO/本地双形态）+ 内容 SHA-256 + 在线查看 + 前端文件选择/缩略图，冒烟 24/24 双形态实测 |

## 三、已拍板决策（勿回退）

1. **编排双轨**：生产一律 Temporal（durable 跨重启恢复）；`CLAIM_PROFILE=local` 走 LocalPipelineRunner 同构状态机（轮询审批表代替 signal），启动即告警非生产编排，重启经 ApplicationReadyEvent 按状态续接等待节点。
2. **无 Key 不装死**：未配 DeepSeek Key 时 AI 端点 503 + 前端优雅降级提示，流程走人工通道，业务不停摆。
3. **报案同步建案**：API 返回前案件行必须已存在（createCase 幂等 + 两条编排路径启动前同步建案），杜绝异步竞态 500。
4. **单队列 P0**：所有 activity 注册 claim-main；DESIGN 4.1 六队列物理隔离属 P1 部署拆分（10.5 节如实标注）。
5. **欺诈评分 P0 为确定性规则评分器**（非模型）；条款检索 P0 无向量库，走规则引擎；支付为模拟 activity + 双账本。
6. **git 听指令**：提交/推送均须用户明示；不提交 data/logs/密钥。
7. **默认 mysql 档位**（2026-11-18 用户「数据库切成mysql」）：MySQL claim 库为默认存储，H2 仅 local 零依赖档保留（原 H2 文件留作备份不删）；docker exec 导中文种子必须 `--default-character-set=utf8mb4`。
8. **批处理调度上 XXL-Job**（2026-11-18 用户「2 上 XXL-Job」+「管理台进 ops 运维工具」）：mysql/default 档由 XXL-Job 2.4.1 调度（中心 Docker :8094 ops-pro 纳管，执行器 claim-pro-executor :9999，图谱 02:30/金标 03:00）；local 档 Spring cron 条件兜底防双调度；启动跑+手动 API 保留；accessToken 双端同值放仓库外。
9. **材料真实文件上传+查看**（2026-11-18 用户「都一会改了」）：上传收 MultipartFile（白名单 jpg/jpeg/png/gif/webp/pdf ≤20MB），MIME 由扩展名推导不信任客户端；SHA-256 按文件内容计算、查看读回复核；MaterialFileStore 与证据链共用 claim.minio 开关（MinIO 生产/本地磁盘开发）；元数据登记通道保留（演示/存量，size_bytes NULL 标记「仅登记」）；存量库增量 DDL deploy/mysql/003_material_files.sql。影像 OCR 识别仍属 P1 未拍板。

## 四、纠正与教训（踩坑 → 规则）

| 事件 | 确立的规则 |
|---|---|
| `./mvnw ... \| tail` 显示成功实则全失败 | 管道 tail 掩盖退出码；构建验证一律 `> log 2>&1; echo EXIT=$?` |
| Lombok TypeTag UNKNOWN 编译崩溃 | 本机 JDK 26，Boot 3.2.5 默认 Lombok 1.18.32 不兼容 → pom 覆盖 `lombok.version=1.18.48` |
| LangChain4j 找不到 ChatLanguageModel | 1.0.1 已改名 ChatModel/StreamingChatModel；两接口默认方法冲突不能同类实现 → 拆双适配器 |
| Temporal client.newWorker 不存在、Worker.start 包私有 | SDK 1.22.3 用 WorkerFactory 模式：newInstance→newWorker→factory.start/shutdown |
| 报案 500 NoSuchElementException | 异步建案竞态 → 同步建案 + 幂等（见决策 3） |
| 结案 payable=0 | pay activity 内必须回写案件 payableCents |
| local 模式详情时间线空 | LocalLogEventPublisher 注入审计环回 Consumer，模拟 MQ 消费归档 |
| 前端定时器风暴（数千请求 ERR_INSUFFICIENT_RESOURCES） | 重渲染前 stopPoll()；异步回调加路由守卫（location.hash 校验）防过期覆盖 |
| 重启后种子案件补件/决策无人消费 | recover() 按状态续接；APPROVING 按最近 COVERAGE 事件区分赔付审批/拒赔确认 |
| H2 大写列名 vs MySQL 小写 | 前端 norm() 与脚本统一 lowercase keys |
| 浏览器 evaluate_script 超 15s 状态混乱 | 拆短调用；借用户页签须授权并还原（共享浏览器守则） |
| ops-pro 起 minio 报 docker-credential-desktop not found | nohup 进程 PATH 缺 ~/.docker/bin → DockerService.composeEnv() 补 PATH（已在 ops-pro 修复） |
| minio 镜像五个渠道全拉不下来 | Docker Hub 直连超时 + 镜像源无仓库；不阻塞 local（LocalFsEvidenceStore），网络恢复后 ops-pro 点启动即可 |

## 五、验证记录（2026-11-18 交付口径）

- 单测 **28/28**（描述规则 2 + 脱敏 4 + 网关模型 2 + 计算器 5 + 双账本/审批 4 + 核保规则 4 + 图谱 4 + 金标 3）。
- `python3 smoke-test.py` **24/24**：报案→材料文件上传/读回/拒绝用例→补件→结案→双账本→证据哈希链→审计→对账零差异→图谱→金标 15 案零漂移；**本地磁盘与 MinIO 双形态各 24/24**。
- 浏览器实测：报案样本→一键补件→人工决策结案；工作台审批通过/驳回；异议升级人工；通知 503 降级气泡；375px 移动布局；材料文件选择上传→表格文件名/大小/哈希→缩略图加载→查看链接（隐藏 iframe 验证，不劫持用户页签）；控制台零报错。
- 重启恢复实测：启动日志「本地管道已恢复 N 个未决案件的等待节点」。
- **如实说明**：桌面视觉截图未做（浏览器面板隐藏无视口），以结构化快照+脚本完成功能验收。

## 六、通用协作原则（与 ride/payment/yiwu/cs 一致）

1. **开箱即用**：零配置一键启动，交付前实际编译+测试+启动+UI 验证。
2. **方案拍板制**：给权衡选项由用户拍板升级幅度，不擅自上重方案。
3. **设计类按商用标准**：生产级 + 主流正式框架，控制点走框架扩展位（与构建类零依赖偏好区分）。
4. **实测文化**：单测+冒烟+浏览器全流程，全绿才算交付；不声称没做过的检查。
5. **中文交流 + 教学式回答**：操作类给可复制命令并实测；概念类通俗解释 + 代码 file:line。
6. **并行会话复查**：多会话同改一工作区（cs-pro 会话共用同套中间件），提交/交付/汇报前重读文件当前状态。
7. **共享浏览器守则**：不劫持用户页签；长等待拆两次调用（15s 超时）。

**一行指令模板**：【构建/设计/运维】+ 目标 + 标准档位 + 完成定义 + 授权范围。
例：「构建，给 claim 加影像 OCR 识别节点，商用标准，完成=单测+smoke+DESIGN 同步，提交推送一起做，删数据先问我。」