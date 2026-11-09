# 共享交换系统 · 实现架构说明

> 对应《共享交换系统架构设计文档 V1.0》。本文说明**实际代码实现**与设计文档的对应关系、关键技术决策与差异，便于评审与后续演进。

## 1. 实现形态

设计文档的部署形态为“微服务 + 引擎 + 中间件”。受交付环境（单机 Windows、无容器/中间件）约束，一期实现采用**模块化单体**：

- 一个可执行单元 `share-app`（Spring Boot 3.2.5，Java 17），按域拆分为 7 个 Maven 模块（`share-auth/catalog/task/share/security/ops/agent`）+ `share-common`；
- 各域**只依赖 `share-common`**，域间无编译期耦合：跨域读走共享库只读表（JdbcTemplate），跨域异步走 Spring 事件（审计/通知/交换指标）；
- 这样既满足文档“代码按域分模块”的要求，又保留按域拆分成独立微服务的路径（替换 Repository 为 Feign/HTTP 即可，边界不变）。

| 设计文档要求 | 本实现 | 演进路径 |
| --- | --- | --- |
| Spring Cloud Alibaba + Nacos | 单进程内模块装配 | 按模块拆进程 + Nacos 注册 |
| SeaTunnel / Flink CDC | `share-task` 内置轻量交换引擎（真实读数/映射/算子/脱敏/写目标） | `engine-proxy` 生成 SeaTunnel/Flink 作业配置 |
| XXL-Job / DolphinScheduler | Spring `ThreadPoolTaskScheduler` + cron 表达式 | 替换为 XXL-Job 执行器 |
| APISIX 网关 | 统一 `/api` 过滤器链（JWT 鉴权、权限拦截、TraceId、CORS）+ 共享 API 路由与限流（应用内） | 路由配置下发 APISIX Admin API |
| Kafka 事件总线 | Spring `ApplicationEvent`（进程内） | 抽换为 Kafka topic：审计/指标/通知 |
| MySQL / Redis / ES / MinIO | H2（MySQL 兼容模式）+ 本地文件区 | `application-mysql.yml` 切 MySQL；文件区换 MinIO；审计检索换 ES |
| Prometheus/Grafana | Micrometer + `/actuator/prometheus` + 平台自建监控聚合 | 接入统一监控平台 |

> H2 默认 `MODE=MySQL`，表结构与 SQL 尽量保持 MySQL 兼容；生产切换只需 `--spring.profiles.active=mysql`。

## 2. 模块职责与依赖

```
share-common   统一响应/错误码/异常、JWT、鉴权过滤器与权限拦截、审计注解+切面、事件模型、脱敏 SPI、工具
   ▲  ▲  ▲  ▲  ▲  ▲  ▲
   │  │  │  │  │  │  └── share-agent    前置节点协议（注册/心跳/命令/分片/摆渡）
   │  │  │  │  │  └────── share-ops      文件交换、审计、监控告警、通知
   │  │  │  │  └───────── share-share    共享服务/开放 API、申请审批授权
   │  │  │  └──────────── share-security 分类分级、脱敏引擎、密钥元数据
   │  │  └─────────────── share-task     交换任务/调度/引擎/加工/质量
   │  └────────────────── share-catalog  数据源接入、数据目录
   └───────────────────── share-auth     认证、用户、角色、组织、RBAC
                              ▲
                          share-app      启动装配 + 静态门户 + OpenAPI + SPA 回退
```

## 3. 关键实现决策

### 3.1 鉴权链
`TraceIdFilter(-100)` → `AuthFilter(-90)`（解析 JWT 写入 `UserContext`，白名单放行）→ `PermissionInterceptor`（`@RequirePermission`，ADMIN 放行，角色权限由 `PermissionProvider` 提供）→ 业务。401/403 均以统一 `Result` 返回。

### 3.2 审计链
`@AuditLog` AOP（`AuditAspect`）采集操作人/IP/UA/耗时/结果/参数/模块 → 发布 `AuditEvent` → `share-ops` 监听入库 `aud_operation_log` → 支撑审计检索、统计、异常行为识别与数据流转追溯（结合 `sw_batch`/`sw_transfer_log`）。

### 3.3 交换引擎
`tk_task.definition` 持久化源/映射/算子/目标/调度；触发后生成 `batchId`，异步执行：
读取源（H2 表/CSV）→ 字段映射与算子链 → 敏感字段经 `MaskService` 脱敏 → 写目标（自动建表 + 幂等 upsert / CSV）→ 记录 `sw_batch`、`sw_transfer_log`、进度与日志 → 发布 `ExchangeEvent`（监控聚合）。失败按指数退避重试，行数/MD5 支持一致性核对。

### 3.4 共享 API 与动态脱敏
`shr_api` 定义 + `shr_credential` 凭据 + 内存 QPS 令牌桶；开放调用 `/api/open/v1/{apiCode}`（白名单，AppKey/AppSecret 鉴权）→ 按 `returnFields` 投影、`maskFields` 经 `MaskService` 动态脱敏 → 写 `shr_call_stat` 计量。

### 3.5 审批流
`apr_apply` + `apr_flow_node`（节点序号/角色/状态）+ `apr_grant`（授权期限）。涉敏资源（`cat_catalog.secret_level >= share.security.sensitive-level`）自动追加安全管理员节点；通过后签发凭据并生成授权；`@Scheduled` 每日 T-7 提醒与到期回收。

### 3.6 脱敏 SPI
`share-common` 定义 `MaskService`/`MaskType` + 兜底实现；`share-security` 提供 `@Primary` 实现（遮蔽/替换/哈希/泛化/加扰/加密），静态脱敏（引擎）与动态脱敏（共享 API/目录预览）共用同一套算法与规则。

## 4. 数据模型

实体表共 48 张，按域分布（详见 `docs/CONTRACT.md` 第 0 节）：

| 域 | 表 |
| --- | --- |
| 认证 | `sys_dept` `sys_user` `sys_role` `sys_role_permission` |
| 目录/数据源 | `ds_source` `ds_source_log` `ds_connector_type` `cat_classify` `cat_catalog` `cat_catalog_item` `cat_catalog_version` |
| 任务 | `tk_task` `tk_task_version` `tk_task_instance` `tk_task_dep` `tk_template` `sw_batch` `sw_transfer_log` `sw_checkpoint` `tk_quality_rule` `tk_quality_report` `tk_quality_issue` |
| 共享/审批 | `shr_api` `shr_api_param` `shr_credential` `shr_call_stat` `apr_apply` `apr_flow_node` `apr_grant` |
| 安全 | `sec_classify_template` `sec_classify_tag` `sec_mask_rule` `sec_key_meta` `sec_mask_log` |
| 运维 | `fl_file` `fl_transfer_task` `fl_transfer_log` `aud_operation_log` `mon_node` `mon_alarm_rule` `mon_alarm_record` `mon_metric_point` `notify_message` `notify_channel` `notify_announcement` `notify_feedback` |
| 前置节点 | `ag_node` `ag_heartbeat` `ag_command` |

- 开发环境 `ddl-auto=update` 自动建表；生产可导出 DDL 后改为 `validate` 并由 DBA 管理；
- 主键统一 `BIGINT IDENTITY`，公共字段（create_time/update_time/create_by/update_by/deleted）由 `BaseEntity` 维护；
- 血缘：`tk_task_version`/`sw_transfer_log` 记录源→任务→目标，`/api/audit/v1/trace` 提供图形化追溯数据。

## 5. 安全设计落地

| 层面 | 实现 |
| --- | --- |
| 认证 | JWT（HS256，access 15min/refresh 7d）；ADMIN/SECURITY 强制短信双因素（演示环境验证码由接口返回）；登录失败计数与锁定 |
| 授权 | 接口级 `@RequirePermission`（RBAC 矩阵）+ 数据级部门域隔离 + 共享凭据授权等级 |
| 分类分级 | 1~4 级模板与标签（目录/字段），分级驱动审批节点与默认脱敏策略 |
| 脱敏 | 6 类算子，静态/动态双通道，规则可配置可试算 |
| 加密 | 数据源口令 AES-256-GCM 落盘；生产 KMS/信封加密；传输由网关 TLS 终止 |
| 审计 | 全量操作 + 数据流转 + 异常行为，保留期默认 180 天 |
| Web 安全 | 参数校验（Jakarta Validation）、排序字段白名单、越权由权限拦截器与数据级校验兜底；上线前应做渗透测试 |

## 6. 与设计文档的差异与待办

1. 引擎能力边界：本实现覆盖全量/增量条件抽取、字段映射、常用算子、目标幂等写入；CDC 日志级增量与大规模并行需接入 Flink CDC。
2. 网关：APISIX 路由/插件为配置持久化 + 应用内等价实现，生产应下发 APISIX。
3. 消息与检索：Kafka/ES 以进程内事件 + 关系库检索替代，接口契约不变。
4. 集群与容灾：开发态单实例 H2；生产需 MySQL 主从、Redis 哨兵、多副本无状态服务、对象存储纠删码。
5. 双因素：短信通道为演示实现，生产对接短信网关；如需可扩展 TOTP。
