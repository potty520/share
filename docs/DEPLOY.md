# 共享交换系统 · 部署与运维手册

> 适用版本：V1.0（对应需求文档 FR-01~FR-14）
> 目标读者：实施、运维、验收人员

## 1. 环境规划

| 环境 | 用途 | 部署形态 |
| --- | --- | --- |
| dev | 开发联调 | 单机 + 内置 H2 + 本地文件区 |
| test | 功能/性能测试 | 单机/容器 + MySQL 8 + MinIO（可选） |
| prod | 生产 | 双副本无状态服务 + MySQL 主从 + 网关 TLS + 对象存储 + 监控 |

### 1.1 服务器基线
| 项 | 建议 |
| --- | --- |
| OS | Linux（openEuler/CentOS/Ubuntu LTS），Windows Server 亦可 |
| CPU/内存 | 应用 8C16G × 2；数据库 8C32G；文件/对象存储按数据量 |
| JDK | 17（LTS），`JAVA_HOME` 指向 JDK17 |
| 数据库 | MySQL 8.0（或达梦/金仓，需同步适配方言） |
| 网络 | 中心平台与前置节点网络可达；建议专线/VPN；HTTP 由 Nginx 终止 TLS |

## 2. 构建

```bat
rem Windows 开发机
cd E:\C++\share-exchange
scripts\mvn.cmd -DskipTests package          rem 产物：share-app\target\share-exchange.jar
scripts\build-web.cmd                         rem 前端构建并复制到 share-app 静态资源
```
Linux：
```bash
export JAVA_HOME=/opt/jdk-17
mvn -DskipTests package
( cd share-web && npm ci && npm run build )
rm -rf share-app/src/main/resources/static && cp -r share-web/dist share-app/src/main/resources/static
mvn -DskipTests package   # 重新打包包含前端
```

## 3. 启动

### 3.1 开发/测试（内置 H2）
```bat
%JAVA_HOME%\bin\java -jar share-app\target\share-exchange.jar
```
默认端口 8080，开发态使用**内存 H2**（每次启动自动建表 + 初始化演示数据，重启即重置，适合演示/联调）。
需要持久化时可切换文件库：`--spring.profiles.active=file`（数据落在 `./data/share-exchange.mv.db`）。
文件交换区与日志始终在 `./data`（`share.file.root` 可配）。

> 说明：个别受限/沙箱环境（如部分安全软件接管文件访问）H2 文件库二次打开可能变为只读，
> 生产环境请直接使用 MySQL，或使用内存 H2 进行演示。

### 3.2 生产（MySQL）
> 已在 Rocky Linux 10.2 + MySQL 8.4.13 + OpenJDK 21 实机验证（建表 61 张、0 DDL 错误、冒烟 39/39）。
> 完整可复现步骤与运维命令见 [`VM_DEPLOY.md`](VM_DEPLOY.md)。

1. 建库建账号：
```sql
CREATE DATABASE share_exchange DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'share'@'%' IDENTIFIED BY '强口令';
GRANT ALL PRIVILEGES ON share_exchange.* TO 'share'@'%';
```
2. 启动：
```bash
export SHARE_JWT_SECRET='≥32字节随机串'
export SHARE_CRYPTO_KEY='高熵密钥（建议接 KMS）'
export SHARE_SEED_ENABLED=false
export SHARE_FILE_ROOT=/data/share/files
java -jar share-exchange.jar --spring.profiles.active=mysql \
  --spring.datasource.url='jdbc:mysql://db:3306/share_exchange?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false' \
  --spring.datasource.username=share --spring.datasource.password='强口令'
```
3. 首次上线建议：`ddl-auto=update` 建表后导出 DDL 评审，再切 `ddl-auto=validate` 由 DBA 管理变更。
4. MySQL 兼容要点（已在源码中处理）：长文本字段用 `TEXT`（避免 utf8mb4 行宽 65535 限制）；
   避免 MySQL 保留字列名（如 `sensitive` → `is_sensitive`）；表元数据查询需同时兼容 `public` 与 `database()` schema。

### 3.3 系统服务（systemd）
```ini
[Unit]
Description=Share Exchange Platform
After=network.target mysql.service

[Service]
User=share
WorkingDirectory=/opt/share-exchange
Environment=JAVA_HOME=/opt/jdk-17
Environment=SHARE_JWT_SECRET=***
Environment=SHARE_CRYPTO_KEY=***
ExecStart=/opt/jdk-17/bin/java -Xms2g -Xmx4g -jar /opt/share-exchange/share-exchange.jar --spring.profiles.active=mysql
Restart=always
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

### 3.4 Nginx 反向代理（TLS）
```nginx
server {
  listen 443 ssl http2;
  server_name share.example.gov.cn;
  ssl_certificate     /etc/nginx/ssl/share.crt;
  ssl_certificate_key /etc/nginx/ssl/share.key;
  ssl_protocols TLSv1.2 TLSv1.3;

  client_max_body_size 220m;

  location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 300s;
  }
}
```

## 4. 前置 Agent 部署（接入方/受限网络）

| 项 | 说明 |
| --- | --- |
| 注册 | 首次启动用安装凭证 `AGENT-INSTALL-2026`（演示值，生产一机一码）调用 `POST /api/agent/v1/register` 获取 nodeId 与节点令牌 |
| 心跳 | 默认 30s 上报 CPU/内存/磁盘与任务进度，超过 90s 未上报判离线并告警 |
| 命令通道 | 常规网络走 REST 长轮询 `GET /api/agent/v1/commands`；生产可换 Kafka 命令 topic |
| 数据传输 | HTTPS 分片上传（`/api/agent/v1/upload/chunk`）；离线场景使用“加密包 + 摆渡导入”（`/api/agent/v1/package/import` 校验签名与清单） |
| 安全 | Agent 不持有中心库权限；任务凭据为短时令牌；安装包内置 JRE，支持 systemd/容器运行 |

## 5. 备份与容灾

| 对象 | 策略 | RPO/RTO |
| --- | --- | --- |
| MySQL | 每日全备 + binlog 增量（实时归档） | RPO ≤ 1h / RTO ≤ 4h |
| 文件区/MinIO | 对象存储纠删码；跨机房同步 | — |
| 配置 | 版本库管理 + 变更审计 | — |
| 日志 | 本地滚动 30 天 + 审计库保留 180 天（可配） | — |

恢复演练：每季度一次，按“数据库恢复 → 文件区校验 → 服务拉起 → 冒烟测试（smoke-test.mjs）”流程执行。

## 6. 监控与告警

| 项 | 方式 |
| --- | --- |
| 应用指标 | `/actuator/prometheus`（Micrometer）；平台内 `/api/monitor/v1/**` 提供业务指标 |
| 节点监控 | 前置节点心跳 + 平台 `/api/monitor/v1/nodes` |
| 告警规则 | 平台内配置（任务失败率、节点离线、API 错误率），支持 Webhook/邮件/短信通道 |
| 日志 | `./data/logs/share-exchange.log`（滚动 30 天）；审计日志独立入库 |
| 链路 | 请求头 `X-Trace-Id` 贯穿；交换批次 `batchId` 关联数据流转 |

## 7. 升级与回滚

1. 升级：备份数据库与 `data` 目录 → 替换 jar（含前端静态资源）→ 滚动重启 → 执行 `scripts\smoke-test.mjs` → 观察 30 分钟告警；
2. 回滚：恢复上一版 jar + 数据库快照；若涉及表结构变更，需按发布单中的降级 SQL 执行；
3. 兼容性：接口保持 `/api/{域}/v1/**` 版本号，破坏性变更升 v2。

## 8. 常见问题

| 现象 | 排查 |
| --- | --- |
| 启动报 `Table not found` | 确认 `spring.jpa.hibernate.ddl-auto=update` 或已执行 DDL |
| 登录返回 40100 | 令牌过期（access 15min）；前端会自动用 refresh 或跳登录 |
| 登录返回 41104 | 该角色需短信验证码：先调 `/api/auth/v1/sms-code` |
| 交换任务实例 FAILED | 查看实例日志与 `sw_transfer_log`；核对源表/文件路径与字段映射 |
| 开放 API 401/403 | 校验 `X-App-Key`、`X-App-Secret` 与凭据状态；确认 API 已上线 |
| 页面刷新 404 | 确认静态资源已打包（SPA 回退由 `SpaWebConfig` 处理），jar 内含 static/index.html |
| 文件上传失败 | 检查 `share.file.root` 目录权限与 `spring.servlet.multipart.max-file-size` |

## 9. 验收自检清单

- [ ] 6 个演示账号可登录（admin/security 需验证码）
- [ ] 数据目录：编目 → 提交 → 审核发布 → 检索 → 详情 → 样例预览（敏感字段已脱敏）
- [ ] 数据源：新增 → 连通测试 → 启停 → 变更历史
- [ ] 交换任务：触发运行 → 实例进度 → 行数/MD5 核对 → 日志
- [ ] 共享申请：consumer 提交 → bizadmin/provider/security 逐级审批 → 授权生成 → 凭据调用开放 API → 到期回收
- [ ] 文件交换：上传 → 校验 → 下载 → 分片续传
- [ ] 安全审计：检索到上述操作，且能按 batchId 追踪数据流转
- [ ] 监控大屏：节点在线状态、任务成功率、API 调用量、告警记录
- [ ] 消息中心：审批待办/结果通知可读
- [ ] `node scripts\smoke-test.mjs` 全部 PASS（允许 SKIP 项有说明）
