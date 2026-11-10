# 共享交换系统（Share Exchange Platform）

> 按《共享交换系统需求文档 V1.0》（FR-01~FR-14）与《共享交换系统架构设计文档 V1.0》实现的**可运行系统**：
> 中心平台 + 前置 Agent + 统一门户，覆盖数据源接入、归集交换、目录编目、任务调度、加工质量、
> 共享服务、申请审批、文件交换、分类分级与脱敏、安全审计、监控告警、统一门户与消息协同。

---

## 1. 快速开始

### 1.1 环境
| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+（已验证 17 / 21） | Spring Boot 3.2.5 要求 ≥17；本机已内置 `_toolchain\jdk\jdk-17.0.2` |
| Maven | 3.9+ | 使用 `scripts\mvn.cmd`（自动切 JDK17、阿里云镜像、工作区本地仓库） |
| Node.js | 18+（建议 20/22） | 仅前端构建需要 |
| 数据库 | 开发态 H2 内存（零安装）；生产/演示 MySQL 8.4 | 切换方式见 `docs/DEPLOY.md`、`docs/VM_DEPLOY.md` |
| 浏览器 | Chrome / Edge 最新版 | 门户访问 |

### 1.2 本地开发启动（Windows + 内置 H2，可选）
```bat
cd E:\C++\share-exchange

rem 1) 构建后端（首次会下载依赖，约 1~2 分钟）
scripts\mvn.cmd -DskipTests package

rem 2) 构建前端并复制到后端静态资源（可选：已提交构建产物）
scripts\build-web.cmd

rem 3) 启动平台（默认 8080；开发态内存 H2，每次启动自动建表+演示数据）
scripts\start.cmd
```
访问：
- 统一门户：<http://127.0.0.1:8080/>
- 接口文档（Swagger/OpenAPI）：<http://127.0.0.1:8080/swagger-ui.html>
- 健康检查：<http://127.0.0.1:8080/api/health>

### 1.3 演示/生产环境（Rocky Linux + MySQL 8.4，已验证）
虚拟机演示环境地址：**<http://192.168.43.128:8080/>**（root / zkzj1024）

| 项 | 值 |
| --- | --- |
| 操作系统 | Rocky Linux 10.2 x86_64（8C/15G/43G） |
| 数据库 | MySQL Community Server 8.4.13（`share_exchange` 库） |
| 运行时 | OpenJDK 21.0.13（应用 JDK17 编译，21 兼容运行） |
| 部署方式 | `/opt/share-exchange` + systemd：`share-exchange`(8080) / `share-exchange-worker`(8081，调度) |
| 中间件 | Redis 6379、Kafka 9092、MinIO 9000、ES 9200、Loki 3100、APISIX 9080/9180、SkyWalking 11800/12800、MySQL 从库 3307 |
| 验证结果 | 启动 0 DDL 错误；冒烟 **39/39 PASS**，网关增强 **11/11 PASS**，mTLS/Nacos/Kafka/ES/MinIO/Loki 均已实测 |

完整部署步骤、运维命令与 MySQL 适配修复记录见 [`docs/VM_DEPLOY.md`](docs/VM_DEPLOY.md)；
可复现脚本见 `deploy/vm/`（环境探查 / 安装 JDK+MySQL / 初始化库 / 部署 systemd 服务）。

### 1.4 演示账号（密码统一 `123456`）
| 账号 | 角色 | 典型演示路径 |
| --- | --- | --- |
| `admin` | 系统管理员 | 全部功能；用户/角色、节点、监控大屏（登录需填图形验证码，点击图片可刷新） |
| `provider` | 数据提供方 | 数据源登记、编目挂接、提交审核、响应共享申请 |
| `consumer` | 数据使用方 | 浏览目录、提交共享申请、调用共享 API、文件下载 |
| `security` | 安全管理员 | 分类分级、脱敏规则、安全审计、涉敏审批 |
| `ops` | 交换运维 | 交换任务、调度、实例排障、监控告警 |
| `bizadmin` | 业务管理员 | 目录审核、共享申请审批 |

### 1.5 冒烟验收
```bat
node scripts\smoke-test.mjs
```
覆盖 40+ 个关键接口（登录/图形验证码、数据源、目录、任务运行与实例、质量、共享 API 与开放调用、审批、脱敏试算、文件、审计、监控、通知、前置节点）。
对虚拟机环境执行：PowerShell 先 `$env:SHARE_BASE='http://192.168.116.128:8080'` 再运行。

---

## 2. 系统架构

### 2.1 分层
```
门户层    Vue3 + Element Plus + Pinia + ECharts（使用方/提供方/管理端/监控大屏）
网关层    Spring Cloud Gateway 形态的内部路由（本实现以统一 /api 前缀 + 鉴权过滤链等价实现）
业务层    share-auth / share-catalog / share-task / share-share / share-security / share-ops / share-agent
引擎层    自研轻量交换引擎（真实数据搬运）+ 调度器（cron）+ 脱敏引擎 SPI + 事件总线
存储层    H2/MySQL（元数据与运行数据）、本地文件区（文件交换/样例数据，可替换 MinIO）
```
> 架构设计文档要求“管控与执行分离、前置机松耦合”：中心平台只做管理（编目/编排/审批/策略/监控），
> 数据搬运由引擎完成，前置 Agent 通过注册/心跳/命令/分片上传协议接入。
> 生产环境可将引擎替换为 SeaTunnel/Flink CDC、网关替换为 APISIX、消息替换为 Kafka、检索替换为 Elasticsearch，
> 服务边界与接口契约保持不变（见 `docs/ARCHITECTURE.md`）。

### 2.2 工程结构
```
share-exchange/
├── pom.xml                    # 父 POM（Spring Boot 3.2.5 / Java 17）
├── share-common/              # 通用层：Result/错误码/异常/JWT/鉴权/审计注解与事件/脱敏 SPI/工具
├── share-auth/                # FR-13 认证、用户、角色、组织、RBAC（实现 PermissionProvider）
├── share-catalog/             # FR-01 数据源接入、FR-03 数据目录（编目/版本/挂载/检索/审核）
├── share-task/                # FR-02 归集、FR-04 任务与调度、FR-05 加工、FR-06 质量
├── share-share/               # FR-07 共享服务与网关、FR-08 申请审批授权
├── share-security/            # FR-10 分类分级、静态/动态脱敏、密钥元数据
├── share-ops/                 # FR-09 文件交换、FR-11 审计、FR-12 监控告警、FR-14 通知
├── share-agent/               # 前置机 Agent：注册/心跳/命令下发/分片上传/离线摆渡
├── share-app/                 # 启动模块（聚合各域 + 内置 H2 + 静态门户）
├── share-web/                 # Vue3 统一门户（Vite 工程）
├── docs/                      # 契约、部署、接口文档
└── scripts/                   # 构建/启动/冒烟测试/本地仓库配置
```
> 代码按域分包、按模块依赖收敛（各域只依赖 `share-common`，跨域通过只读表与 Spring 事件解耦），
> 可按文档第 4 章拆分为独立微服务（Nacos 注册、独立库、Feign/HTTP 调用）。

### 2.3 核心流程实现
| 流程 | 实现位置 | 关键点 |
| --- | --- | --- |
| 共享申请审批闭环 | `share-share` | 多级节点（业务管理员→提供方→安全管理员，涉敏资源自动加节点）、通过后自动签发凭据与授权、到期提醒（T-7）与自动回收 |
| 批量交换执行 | `share-task` | 源/目标支持平台内置表、外部 JDBC 表与 CSV；`batchId` 贯穿、字段映射/算子链/脱敏、目标端自动建表与幂等、失败重试（指数退避）、行数与 MD5 核对、实例日志 |
| 实时增量 | `share-task` | 外部 JDBC 表按时间戳/自增字段增量抽取；批次成功后推进水位，失败不推进；支持 `sw_checkpoint` 断点记录 |
| 共享 API 调用 | `share-share` + `/api/open/v1/{apiCode}` | AppKey/AppSecret 鉴权、IP 黑白名单（CIDR）、API 版本校验、熔断、数据库集群级限流 + 单机令牌桶、动态脱敏、调用计量 |
| 质量检核 | `share-task` | 平台库与外部 JDBC 目标库检核；唯一性/完整性/格式/一致性/时效性；`checkCron` 定时调度；报告、问题工单与得分趋势 |
| 文件交换 | `share-ops` | 分片断点续传、MD5/SHA256 校验、目录监听与定时收发；LOCAL/FTP/FTPS/SFTP 互传、GZIP 压缩、AES-256-GCM 加密、失败自动重试 |
| 安全管控 | `share-security` | 1~4 级分类分级、6 类脱敏算法（遮蔽/替换/哈希/泛化/加扰/加密）、静态与动态双通道 |
| 审计与监控 | `share-ops` | `@AuditLog` 切面 → 事件 → 审计库；批次事件 → 指标聚合 → 告警规则 → 通知 |

---

## 3. 功能实现对照（需求 → 代码）

| 需求 | 模块 | 主要接口前缀 |
| --- | --- | --- |
| FR-01 数据源接入管理 | share-catalog | `/api/datasource/v1/**` |
| FR-02 数据归集与同步 | share-task / share-agent | `/api/task/v1/**`、`/api/agent/v1/**` |
| FR-03 数据目录管理 | share-catalog | `/api/catalog/v1/**` |
| FR-04 交换任务管理 | share-task | `/api/task/v1/**` |
| FR-05 数据加工处理 | share-task | `/api/task/v1/operators`、`/preview` |
| FR-06 数据质量管理 | share-task | `/api/quality/v1/**` |
| FR-07 共享服务管理 | share-share | `/api/share/v1/**`、`/api/open/v1/**` |
| FR-08 共享申请与审批 | share-share | `/api/approval/v1/**` |
| FR-09 文件交换 | share-ops | `/api/file/v1/**` |
| FR-10 数据安全管理 | share-security | `/api/security/v1/**` |
| FR-11 安全审计与日志 | share-ops | `/api/audit/v1/**` |
| FR-12 监控告警与运维 | share-ops | `/api/monitor/v1/**` |
| FR-13 统一门户与用户管理 | share-auth + share-web | `/api/auth/v1/**`、`/api/user/v1/**`、门户页面 |
| FR-14 消息与协同 | share-ops | `/api/notify/v1/**` |

接口规范与字段约定见 [`docs/CONTRACT.md`](docs/CONTRACT.md)；在线文档见 `/swagger-ui.html`。

---

### 2.4 已实测增强能力（2026-11）
| 能力 | 实现 | 实测结果 |
| --- | --- | --- |
| 外部 JDBC 源/目标 | `JdbcConnectionFactory` + `SourceReader`/`TargetWriter` | 外部源全量 7 行、增量二次仅读 2 行；外部目标 7 行写入，核对 PASS |
| 外部目标质量检核 | `QualityService` 按规则 `targetId` 切换连接 | `ext_orders_target` 唯一性 100 分、完整性 1 条空金额 |
| 质量定时调度 | `QualityScheduler` | `*/10 * * * * ?` 规则自动执行成功 |
| 审计保留清理 | `AuditService.cleanupExpired(days, mode)` | `LOGICAL`/`ARCHIVE`（CSV 归档后物理删除）/`PHYSICAL`；实测归档文件生成且库内记录物理删除 |
| 网关 IP 黑白名单 | `IpAccessService`（精确 IP / 通配符 / CIDR v4+v6） | 白名单外 42406 拒绝、白名单内放行、黑名单优先拒绝 |
| API 版本管理 | `shr_api_version` 快照 + 发布/回滚 | 上线自动快照 v1，手工发布 v2，回滚 v1 生成 v3 且配置恢复 |
| 密钥轮换 | `ShareCredentialService.rotate` + 旧密钥宽限期 | 新密钥立即生效，旧密钥 24h 内可用，宽限期到点后 42403 拒绝 |
| 网关熔断 | `CircuitBreakerService`（失败窗口/半开探测） | 连续 5 次查询失败后第 6 次返回 42405（HTTP 503），复位接口恢复 |
| 集群级限流 | `ClusterRateLimitService`（`shr_gateway_rate` 秒级原子计数） | 与单机令牌桶双重校验，多实例共享同一额度 |
| Prometheus 指标 | `micrometer-registry-prometheus` | `/actuator/prometheus` 返回 JVM/Logback 等真实指标 |
| 文件远端传输 | `RemoteFileService`（JSch / Commons Net） | SFTP 上传下载、FTP 上传下载均实测成功，MD5 一致；GZIP 与 AES-GCM 组合可还原原文；不可达目标按配置重试 2 次后失败 |

> 已打包驱动（本轮未做真实连通测试）：PostgreSQL 42.7.4、SQL Server 12.8.1.jre11、Oracle ojdbc11 23.5、ClickHouse 0.6.5。


## 4. 关键设计说明

### 4.1 统一响应与错误码
```json
{ "code": 0, "message": "success", "traceId": "…", "data": {}, "page": { "pageNum": 1, "pageSize": 10, "total": 0, "pages": 0 } }
```
业务错误码按域分段（如 `42201` 数据源不存在、`42411` 申请单不存在），见 `share-common/.../ErrorCode.java`。
每个请求带 `X-Trace-Id`，贯穿日志、审计与交换批次，便于全链路定位。

### 4.2 认证与权限
- 登录签发 JWT（access 15 分钟 / refresh 7 天），`Authorization: Bearer <token>`
- 登录强制图形验证码（PNG 图片 + 2 分钟有效 + 最多 5 次尝试；`share.auth.captcha.*` 可配，开启 Redis 后多实例共享）
- 接口级权限：`@RequirePermission(PermissionConstants.X)` + 角色权限矩阵（`SeedConstants.ROLE_PERMISSIONS`）
- 数据级权限：目录按部门域隔离、共享 API 按凭据授权等级返回脱敏结果

### 4.3 安全
- 数据源口令 AES-256-GCM 加密落盘（`CryptoUtil`，生产建议接入 KMS）
- 传输：生产由 Nginx/APISIX 终止 TLS 1.2+；前置 Agent 设计为 mTLS
- 脱敏：静态（交换管道）+ 动态（共享 API 响应）统一走 `MaskService` SPI，算法可扩展
- 审计：全量操作留痕，保留期可配（默认 180 天，满足合规留存 ≥ 6 个月）

### 4.4 幂等与可靠性
- 每次交换生成全局唯一 `batchId`，目标端按批次判重；文件按 `文件名+MD5` 判重
- 失败重试指数退避（默认 3 次），仍失败进入告警 + 人工处置入口
- 断点：文件分片清单、交换 checkpoint 持久化

---

## 5. 配置说明

`share-app/src/main/resources/application.yml` 关键项：

| 配置 | 说明 |
| --- | --- |
| `share.jwt.secret` | JWT 密钥（≥32 字节，生产用环境变量 `SHARE_JWT_SECRET` 覆盖） |
| `share.crypto.key` | 敏感字段加密主密钥（生产用 `SHARE_CRYPTO_KEY` 或 KMS） |
| `share.file.root` | 文件交换存储根目录（默认 `./data/files`） |
| `share.engine.*` | 交换引擎批次大小、重试次数与间隔、单次最大行数 |
| `share.monitor.*` | 心跳超时阈值、审计保留天数、清理模式（LOGICAL/ARCHIVE/PHYSICAL）、归档目录 |
| `share.gateway.cluster-rate-limit.enabled` | 是否启用数据库集群级限流 |
| `share.gateway.circuit.*` | 熔断失败阈值、统计窗口、冷却时间 |
| `share.seed.enabled` | 是否初始化演示数据 |

生产（MySQL）配置见 `application-mysql.yml` 与 `docs/DEPLOY.md`。

---

## 6. 测试与验收

| 项 | 方式 |
| --- | --- |
| 接口冒烟 | `node scripts\smoke-test.mjs`（40+ 检查项，输出 PASS/FAIL/SKIP） |
| 端到端演示 | 门户按角色走：编目→审核→申请→审批→授权→开放 API 调用→审计追踪 |
| 一致性 | 任务实例详情中的行数/MD5 核对结果；`/api/task/v1/instances/{id}/verify` |
| 审计闭环 | 任意写操作后在“安全审计”检索到操作记录与数据流转链路 |
| 网关增强 | IP 黑白名单、版本发布/回滚、密钥轮换宽限期、熔断与集群限流（见 `scripts/gateway-test.mjs`） |

---

## 7. 后续演进（与架构设计文档对齐）

1. 服务拆分：按模块边界拆为独立进程，注册 Nacos，服务间 mTLS；
2. 引擎替换：`engine-proxy` 生成 SeaTunnel/Flink CDC 作业并回收指标；
3. 网关替换：当前内置网关已覆盖 IP ACL、版本校验、熔断与集群限流；生产可平滑替换为 APISIX（jwt-auth/limit-req/prometheus/serverless 插件）；
4. 存储替换：MySQL 主从、Redis 会话与限流、Kafka 事件总线、Elasticsearch 检索与审计、MinIO 对象存储；
5. 可观测性：Micrometer → Prometheus、SkyWalking（batchId 作为追踪标签）、Loki 日志。

---

*需求文档与架构设计文档原文位于 `C:\Users\37848\WorkBuddy\2026-11-09-10-28-10\`。*
