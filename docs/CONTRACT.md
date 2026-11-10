# 共享交换系统 · 实现契约（开发协作基线）

> 本文件是各域并行开发的**唯一接口与数据约定**。任何跨域字段/表名/路径变更，必须先改本文件。
> 对应文档：《共享交换系统需求文档 V1.0》FR-01~FR-14、《共享交换系统架构设计文档 V1.0》。

## 0. 工程与分工

- 工程根目录：`E:\C++\share-exchange`（Maven 多模块 + Vue3 前端）
- 统一响应/异常/JWT/权限/审计/事件都在 `share-common`，**各域不得修改 common**
- 每个域只写自己模块的 `src/main/java/com/share/<域>/**` 与 `src/main/resources`；`pom.xml` 如需新增依赖向 Lead 申请
- 构建：`scripts\mvn.cmd -pl <模块> -am -DskipTests compile`（已内置 JDK 17 与阿里云镜像）
- 运行：Lead 统一在 8080 端口启动 `share-app`，各域不要自行 `spring-boot:run`

### 表归属（JPA `@Table(name=...)` 必须与下表一致）

| 域模块 | 表 |
| --- | --- |
| share-auth | `sys_dept` `sys_user` `sys_role` `sys_role_permission` |
| share-catalog | `ds_source` `ds_source_log` `ds_connector_type` `cat_classify` `cat_catalog` `cat_catalog_item` `cat_catalog_version` |
| share-task | `tk_task` `tk_task_version` `tk_task_instance` `tk_task_dep` `tk_template` `sw_batch` `sw_transfer_log` `sw_checkpoint` `tk_quality_rule` `tk_quality_report` `tk_quality_issue` |
| share-share | `shr_api` `shr_api_param` `shr_credential` `shr_call_stat` `apr_apply` `apr_flow_node` `apr_grant` |
| share-security | `sec_classify_template` `sec_classify_tag` `sec_mask_rule` `sec_key_meta` `sec_mask_log` |
| share-ops | `fl_file` `fl_transfer_task` `fl_transfer_log` `aud_operation_log` `mon_node` `mon_alarm_rule` `mon_alarm_record` `mon_metric_point` `notify_message` `notify_channel` `notify_announcement` `notify_feedback` |
| share-agent | `ag_node` `ag_heartbeat` `ag_command` |

### 跨域只读约定（共享库）

本项目为“模块化单体 + 共享库”，跨域**只读**通过 `JdbcTemplate` 按表名查询（禁止读其他域的 JPA Repository），写只能写本域表：

| 读取方 | 可只读的表 | 用途 |
| --- | --- | --- |
| share-catalog | `sys_dept` | 部门名称回填 |
| share-task | `ds_source`、`cat_catalog` | 取数据源连接信息、目录挂接信息 |
| share-share | `cat_catalog`、`ds_source`、`sec_mask_rule`、`sec_classify_tag` | 共享服务绑定目录/数据源、动态脱敏 |
| share-security | `cat_catalog`、`sys_user` | 分级打标与权限校验 |
| share-ops | `tk_task_instance`、`sw_batch`、`shr_call_stat`、`ag_node`、`sys_user` | 审计/监控聚合 |
| share-agent | `tk_task` | 任务下发 |

跨域**异步**一律用 Spring 事件（定义在 `share-common`）：
`AuditEvent`（操作审计）、`NotifyEvent`（通知）、`ExchangeEvent`（交换批次指标）。

## 1. 统一约定

### 1.1 响应体
```json
{ "code": 0, "message": "success", "traceId": "…", "data": {}, "page": {"pageNum":1,"pageSize":10,"total":0,"pages":0} }
```
分页接口返回 `Result.ok(list, total, pageNum, pageSize)`；错误码见 `ErrorCode`。

### 1.2 认证
- 登录后 `Authorization: Bearer <accessToken>`；access 15 分钟、refresh 7 天
- 接口权限用 `@RequirePermission(PermissionConstants.X)`，ADMIN 自动放行
- 当前用户：`UserContext.get()`；操作审计用 `@AuditLog(action="…", targetType="…")`
- 白名单：`/api/auth/v1/login|refresh|captcha|mfa/verify`、`/api/agent/v1/register`、`/api/open/**`、`/api/health`

### 1.3 分页与命名
- 查询参数：`pageNum`（默认 1）、`pageSize`（默认 10）、业务过滤参数
- REST 风格：`/api/{域}/v1/{资源}`，GET 列表/详情、POST 新增、PUT 更新、DELETE 删除
- 时间类型统一 `LocalDateTime`（ISO 字符串）；金额/大整数用 `Long`

### 1.4 内置角色与演示账号（密码统一 `123456`）
| 用户名 | 角色 | 说明 |
| --- | --- | --- |
| admin | ADMIN | 系统管理员（全部权限） |
| provider | PROVIDER | 数据提供方（挂数据源、编目、响应申请） |
| consumer | CONSUMER | 数据使用方（找数据、提申请、调 API） |
| security | SECURITY | 安全管理员（分级、脱敏、审计） |
| ops | OPS | 交换运维（任务、调度、监控） |
| bizadmin | BIZ_ADMIN | 业务管理员（目录审核、审批） |

角色权限矩阵见 `SeedConstants.ROLE_PERMISSIONS`。

## 2. 各域接口清单

> 下列为**必须实现**的接口；“（可选）”为加分项。所有变更类接口加 `@AuditLog`。
> 响应数据字段命名用小驼峰；金额/行数用数值类型。

### 2.1 share-auth（FR-13）
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/auth/v1/captcha` | 返回 `{captchaId,image,expiresIn,code?}`；`image` 为 PNG data URI，`code` 仅在 `share.auth.captcha.expose-code=true` 时回传（演示/联调） |
| POST | `/api/auth/v1/login` | 入参 `{username,password,captchaId,captchaCode}`；返回 `{accessToken,refreshToken,expiresIn,user:{id,username,realName,roleCode,deptId,deptName},permissions:[]}`；验证码 2 分钟有效、用后作废 |
| POST | `/api/auth/v1/refresh` | `{refreshToken}` → 新令牌 |
| POST | `/api/auth/v1/logout` | 令牌失效（Redis 缺失时用内存黑名单即可） |
| GET | `/api/auth/v1/profile` | 当前用户 + permissions + `menus`（前端菜单） |
| POST | `/api/auth/v1/password` | `{oldPassword,newPassword}` |
| GET/POST/PUT/DELETE | `/api/user/v1/users[/{id}]` | 用户管理（列表支持 keyword/deptId/roleCode/status） |
| PUT | `/api/user/v1/users/{id}/status` | `{status}` 启用/停用 |
| PUT | `/api/user/v1/users/{id}/reset-password` | 重置口令 |
| GET/POST/PUT/DELETE | `/api/user/v1/depts[/{id}]` | 组织树 |
| GET/POST/PUT | `/api/user/v1/roles[/{id}]` | 角色与权限 |
| GET | `/api/user/v1/permissions` | 全部权限点字典 |
| GET | `/api/user/v1/overview` | 用户/角色/部门统计（工作台用） |

实现 `PermissionProvider`（角色→权限，带缓存）。

### 2.2 share-catalog（FR-01 / FR-03）
数据源：
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST/PUT/DELETE | `/api/datasource/v1/sources[/{id}]` | 列表（keyword/type/status）、新增、修改、删除；口令 AES 加密存储，列表脱敏返回 |
| GET | `/api/datasource/v1/sources/{id}` | 详情（口令不回显） |
| POST | `/api/datasource/v1/sources/{id}/test` | 连通性测试（真实 TCP 探测 + 类型校验，返回延迟/版本信息） |
| PUT | `/api/datasource/v1/sources/{id}/status` | `{status}` 启用/停用 |
| GET | `/api/datasource/v1/sources/{id}/history` | 变更历史 |
| GET | `/api/datasource/v1/types` | 数据源类型字典（MySQL/Oracle/PG/SQLServer/达梦/Hive/ClickHouse/FTP/SFTP/OSS/Kafka/HTTP/WebService） |

目录：
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/catalog/v1/resources` | 列表：keyword/classifyId/deptId/shareType/status/tag，默认只返回已发布（`all=true` 查全部） |
| POST/PUT/DELETE | `/api/catalog/v1/resources[/{id}]` | 编目维护（含数据项 items、挂接 sourceId/tableName/filePath/apiUrl、密级 level） |
| GET | `/api/catalog/v1/resources/{id}` | 详情（含 items、版本、挂接信息） |
| POST | `/api/catalog/v1/resources/{id}/submit` | 提交审核（DRAFT→PENDING） |
| POST | `/api/catalog/v1/resources/{id}/audit` | `{approved,comment}` 审核（PENDING→PUBLISHED/REJECTED） |
| POST | `/api/catalog/v1/resources/{id}/offline` | 下架（PUBLISHED→OFFLINE） |
| GET | `/api/catalog/v1/resources/{id}/versions` | 版本列表 |
| GET | `/api/catalog/v1/resources/{id}/preview` | 样例数据预览（从挂接数据源/文件/接口取前 N 行，敏感字段按脱敏规则处理） |
| GET/POST/PUT/DELETE | `/api/catalog/v1/classifies[/{id}]` | 分类树（多维度：主题/部门/领域，`dimension` 字段） |
| GET | `/api/catalog/v1/stats` | 目录总数/已发布/待审核/涉敏数/按部门分布 |

状态机：`DRAFT → PENDING → PUBLISHED / REJECTED`，`PUBLISHED → OFFLINE`。

### 2.3 share-task（FR-02/04/05/06）
任务：
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST/PUT/DELETE | `/api/task/v1/tasks[/{id}]` | 任务定义（源/字段映射/算子链/目标/调度策略，JSON 存 `definition` 字段） |
| GET | `/api/task/v1/tasks/{id}` | 详情（含映射规则、依赖、最近实例） |
| POST | `/api/task/v1/tasks/{id}/enable|disable` | 启停 |
| POST | `/api/task/v1/tasks/{id}/run` | 手工触发 → `{instanceId, batchId}` |
| POST | `/api/task/v1/tasks/{id}/stop` | 终止当前实例 |
| GET | `/api/task/v1/tasks/{id}/instances` | 实例列表 |
| POST | `/api/task/v1/tasks/batch-enable` | `{ids:[],enabled:true}` 批量启停 |
| GET/POST/DELETE | `/api/task/v1/tasks/{id}/deps[/{upId}]` | 依赖（保存时做环检测，返回 `TASK_DAG_CYCLE`） |
| GET | `/api/task/v1/templates` | 任务模板 |
| GET | `/api/task/v1/instances` | 全局实例列表（status/taskId/时间范围） |
| GET | `/api/task/v1/instances/{id}` | 实例详情（batchId、行数、耗时、校验值、错误） |
| GET | `/api/task/v1/instances/{id}/logs` | 运行日志（分页/尾部） |
| POST | `/api/task/v1/instances/{id}/rerun` | 重跑 |
| POST | `/api/task/v1/instances/{id}/verify` | 一致性核对（行数/MD5 比对，产出核对结果） |
| GET | `/api/task/v1/batches?batchId=` | 批次查询（含流转日志） |
| GET | `/api/task/v1/operators` | 加工算子字典 |
| POST | `/api/task/v1/preview` | 加工试运行：`{sampleRows:[{}],mapping:[],operators:[]}` → 转换后样例 |

引擎（本域内实现，模拟 SeaTunnel，但数据真实搬运）：
- 源端支持：数据源表（H2/MySQL 可连）、CSV 文件、内置样例表 `demo_*`
- 目标端：H2 目标表、CSV 文件
- 执行：`ExecutorService` 异步，逐批读→字段映射→算子链→脱敏（`MaskService`）→写入；批次号 `IdGen.batchId()`；记录 `sw_batch`、实例进度、日志；失败按 `share.engine.retry-times` 指数退避重试；发布 `ExchangeEvent`
- 调度：Spring `ThreadPoolTaskScheduler` + cron 解析（`CronExpression`），启动时加载启用任务
- 幂等：目标端按 `batchId` 记录已写入批次，重复不重写

调度：
| POST | `/api/task/v1/tasks/{id}/schedule` | `{cron,enabled}` 设置调度 |
| GET | `/api/task/v1/schedules` | 调度计划列表 |

质量（FR-06）：
| GET/POST/PUT/DELETE | `/api/quality/v1/rules[/{id}]` | 规则：完整性/唯一性/规范性/一致性/及时性 |
| POST | `/api/quality/v1/rules/{id}/run` | 执行检核 → 报告 |
| GET | `/api/quality/v1/reports` / `/reports/{id}` | 报告列表/详情（得分、问题明细） |
| GET | `/api/quality/v1/issues` | 问题工单 |
| PUT | `/api/quality/v1/issues/{id}/close` | 整改闭环 |

### 2.4 share-share（FR-07 / FR-08）
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST/PUT/DELETE | `/api/share/v1/apis[/{id}]` | 共享 API 定义（绑定 catalogId/sourceId/表名/返回字段/参数/QPS） |
| POST | `/api/share/v1/apis/{id}/online|offline` | 上线/下线（生成“网关路由”，持久化路由配置） |
| POST | `/api/share/v1/apis/{id}/test` | 在线调试 `{params:{}}` → 数据（动态脱敏） |
| GET | `/api/share/v1/apis/{id}/doc` | 自动生成 API 文档（OpenAPI 片段） |
| GET/POST | `/api/share/v1/credentials` | 凭据列表/签发 |
| PUT | `/api/share/v1/credentials/{id}/revoke` | 吊销 |
| GET | `/api/share/v1/stats?apiId=&from=&to=` | 调用量/成功率/耗时统计 |
| GET | `/api/open/v1/{apiCode}` | **开放调用**（AppKey 鉴权、QPS 限流、动态脱敏、计量落库），支持 `pageNum/pageSize` 与参数 |
| POST/GET | `/api/approval/v1/applies` | 提交申请 / 我的申请与待办（status/mine/role 过滤） |
| GET | `/api/approval/v1/applies/{id}` | 申请详情（含审批节点轨迹） |
| POST | `/api/approval/v1/applies/{id}/approve|reject|withdraw|transfer|renew` | 审批动作（多级：业务管理员→提供方→安全管理员(涉敏)） |
| GET | `/api/approval/v1/grants` | 授权列表（含到期时间） |
| POST | `/api/approval/v1/grants/{id}/revoke` | 回收 |
| GET | `/api/approval/v1/flow-config` | 审批流节点配置 |

- 审批通过 → 自动签发 `shr_credential`、生成 `apr_grant`，发布 `NotifyEvent`
- 定时任务：每日扫描到期授权（T-7 提醒、到期回收），发布 `NotifyEvent`
- 涉敏判定：读 `cat_catalog.level >= share.security.sensitive-level` 则追加安全管理员节点

### 2.5 share-security（FR-10）
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST/PUT/DELETE | `/api/security/v1/templates[/{id}]` | 分类分级模板（1~4 级） |
| GET/POST/DELETE | `/api/security/v1/tags[/{id}]` | 分级标签（目标：CATALOG/FIELD） |
| GET/POST/PUT/DELETE | `/api/security/v1/mask-rules[/{id}]` | 脱敏规则（类型/参数/生效字段） |
| POST | `/api/security/v1/mask-rules/test` | 规则试算 `{maskType,param,value}` |
| GET/POST | `/api/security/v1/keys` | 密钥元数据（仅存 alias/状态，不存明文） |
| POST | `/api/security/v1/keys/{id}/rotate` | 轮换 |
| GET | `/api/security/v1/overview` | 分级分布/规则数/敏感资源数 |
| PUT | `/api/security/v1/grants/visibility` |（可选）数据级权限：部门可见范围 |

必须实现 `MaskService`（`@Primary @Service`）：MASK/REPLACE/HASH/GENERALIZE/PERTURB/ENCRYPT。

### 2.6 share-ops（FR-09/11/12/14）
文件：
| GET/POST/DELETE | `/api/file/v1/files[/{id}]` | 文件列表/上传(multipart)/删除 |
| GET | `/api/file/v1/files/{id}/download` | 下载（流） |
| POST | `/api/file/v1/files/{id}/verify` | MD5/SHA256 校验 |
| POST | `/api/file/v1/uploads/chunk` / `/uploads/complete` / GET `/uploads/{uploadId}` | 分片断点续传 |
| GET/POST/PUT/DELETE | `/api/file/v1/transfer-tasks[/{id}]` | 定时收发任务（含目录监听 watchPath） |
| POST | `/api/file/v1/transfer-tasks/{id}/run` | 立即执行 |
| GET | `/api/file/v1/stats` | 文件数/总量/传输成功率 |

审计：
| GET | `/api/audit/v1/logs` | 审计检索（operator/action/module/时间范围/result） |
| GET | `/api/audit/v1/logs/{id}` | 详情 |
| GET | `/api/audit/v1/stats` | 按模块/动作/结果统计（日趋势） |
| GET | `/api/audit/v1/trace?batchId=` | 数据流转追踪（源→任务→目标） |
| GET | `/api/audit/v1/anomalies` | 异常行为（越权尝试/批量导出/非工作时间） |
| GET | `/api/audit/v1/export` | 审计报表导出（CSV） |

监控：
| GET | `/api/monitor/v1/overview` | 平台总览（节点在线、任务成功率、API QPS、告警数） |
| GET | `/api/monitor/v1/nodes` | 节点状态（含 CPU/内存/磁盘） |
| GET | `/api/monitor/v1/metrics?type=&from=&to=` | 指标趋势（交换量、成功率、耗时） |
| GET/POST/PUT/DELETE | `/api/monitor/v1/alarm-rules[/{id}]` | 告警规则（阈值/持续/级别/通知渠道） |
| GET | `/api/monitor/v1/alarms` | 告警记录 |
| POST | `/api/monitor/v1/alarms/{id}/ack` | 确认告警 |
| GET | `/api/monitor/v1/reports?period=day|week|month` | 运维报表（交换量、部门共享排行、任务健康度） |

通知：
| GET | `/api/notify/v1/messages` | 我的消息（unread 过滤） |
| POST | `/api/notify/v1/messages/{id}/read`、`/messages/read-all` | 已读 |
| GET/POST/PUT | `/api/notify/v1/channels[/{id}]` | 外部通道（邮件/短信/Webhook）配置 |
| GET/POST | `/api/notify/v1/announcements` | 公告 |
| POST/GET | `/api/notify/v1/feedback` | 资源使用反馈 |

事件监听：`@EventListener` 消费 `AuditEvent`/`NotifyEvent`/`ExchangeEvent` 落库并聚合指标（`mon_metric_point` 按小时聚合）。

### 2.7 share-agent（FR-02 前置节点）
| POST | `/api/agent/v1/register` | 安装凭证注册 → 节点身份 + 证书指纹 |
| POST | `/api/agent/v1/heartbeat` | 30s 心跳（存活/资源/任务进度） |
| GET | `/api/agent/v1/commands` | 长轮询拉取任务命令 |
| POST | `/api/agent/v1/commands/{id}/ack` | 命令执行结果回传 |
| POST | `/api/agent/v1/upload/chunk` | 加密分片上传 |
| POST | `/api/agent/v1/package/import` | 离线摆渡包导入（签名+清单校验） |
| GET | `/api/agent/v1/nodes` | 节点列表（管理端） |
| GET | `/api/agent/v1/nodes/{id}` | 节点详情/心跳历史 |

## 3. 种子数据（各域自建，保证演示可点通）

- 组织：数据中心(1)、财务部(2)、运营管理部(3)、人力资源部(4)、平台运维部(9)
- 数据源（share-catalog）：`DS-TAX-MYSQL`（财务部 MySQL，启用）、`DS-MARKET-PG`（PostgreSQL）、`DS-HRSS-FILE`（SFTP 文件）、`DS-BUS-KAFKA`（Kafka）、`DS-OPEN-HTTP`（HTTP 接口）
- 目录资源（share-catalog）：企业基础信息、自然人基础信息、社保参保信息、市场主体登记信息、不动产登记信息、公共信用信息（含涉敏字段，level 3/4）
- 任务（share-task）：企业基础信息全量同步、社保参保增量同步、信用信息定时归集（含历史实例与批次）
- 共享 API（share-share）：企业基础信息查询、社保参保查询、信用信息查询（已上线）+ 凭据
- 审批（share-share）：2 条待办申请 + 1 条已通过
- 脱敏规则（share-security）：手机号遮蔽、身份证遮蔽、姓名替换、地址泛化
- 告警规则（share-ops）：任务失败、节点离线、API 错误率
- 文件（share-ops）：2 个演示文件 + 1 个传输任务
- 节点（share-agent）：2 个演示前置节点（1 在线 1 离线）

种子实现要求：`@Component @Order(n) implements CommandLineRunner`，先判断 `count()==0`，通过 `@Value("${share.seed.enabled:true}")` 控制。

## 4. 前端（share-web，Vue 3 + Vite + Element Plus + Pinia + ECharts）

- 统一布局：左侧菜单（按权限显示）+ 顶部栏（消息/用户/退出）
- 页面：登录、工作台、数据目录（列表/详情/编目管理）、数据源、交换任务（列表/详情/新建）、申请审批（我的申请/待办）、共享服务（列表/详情/调试）、安全管控（分级/脱敏）、文件交换、审计日志、监控大屏、消息中心、用户与角色、前置节点
- 请求封装：`src/api/request.js`（baseURL `/api`、注入 Bearer、统一错误提示、401 跳登录）
- 本地开发 `pnpm dev`（5173，代理 `/api` → `http://127.0.0.1:8080`）；构建产物 `dist` 由 Lead 复制到 `share-app/src/main/resources/static`
- 演示优先：所有页面用真实接口，接口未就绪时页面可显示空态/加载态，不得硬编码假数据
