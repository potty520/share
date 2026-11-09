# 共享交换系统 · 虚拟机（Rocky Linux + MySQL）部署记录

> 部署对象：`192.168.116.128`（VMware 虚拟机，Rocky Linux 10.2）
> 部署时间：2026-11-09
> 用途：用真实 MySQL 替代内嵌 H2，验证生产数据库路径与完整业务链路

## 1. 环境信息

| 项 | 值 |
| --- | --- |
| 操作系统 | Rocky Linux 10.2 (Red Quartz) x86_64 |
| 主机名 | localhost.localdomain |
| 配置 | 8 vCPU / 15 GB 内存 / 43 GB 磁盘（可用 38 GB） |
| 登录账号 | `zk`（已配置 wheel 组免密 sudo；口令在部署时设置，不写入仓库） |
| 数据库 | MySQL Community Server 8.4.13（官方 el10 仓库） |
| 运行时 | OpenJDK 21.0.13（应用由 JDK 17 编译，21 兼容运行） |
| 应用目录 | `/opt/share-exchange`（jar + `data/files` + `data/logs`） |
| 访问地址 | <http://192.168.116.128:8080/> |
| 服务管理 | `systemctl {start\|stop\|restart\|status\|enable} share-exchange` |
| 开机自启 | 已启用（`systemctl is-enabled share-exchange` → enabled） |
| 防火墙 | firewalld 放行 `8080/tcp` |
| SELinux | 演示环境切换为 permissive（生产应使用 `semanage fcontext` 打标签） |

## 2. 部署步骤（可复现）

### 2.1 安装 JDK 与 MySQL
```bash
# JDK
sudo dnf -y install java-21-openjdk-headless

# MySQL 8.4 官方仓库（Rocky 10 使用 el10 仓库）
sudo tee /etc/yum.repos.d/mysql-community.repo >/dev/null <<'EOF'
[mysql84-community]
name=MySQL 8.4 Community Server
baseurl=https://repo.mysql.com/yum/mysql-8.4-community/el/10/$basearch/
enabled=1
gpgcheck=0
module_hotfixes=1
EOF
sudo dnf -y install mysql-community-server
sudo systemctl enable --now mysqld
```

### 2.2 初始化数据库
```bash
# 取初始 root 临时密码
sudo grep 'temporary password' /var/log/mysqld.log
# 口令通过环境变量提供（勿写入仓库）
export MYSQL_ROOT_PASSWORD='<强口令>' MYSQL_SHARE_PASSWORD='<强口令>'
sudo mysql --connect-expired-password -uroot -p'临时密码' \
  -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '$MYSQL_ROOT_PASSWORD';"
sudo mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS share_exchange DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'share'@'%' IDENTIFIED BY '$MYSQL_SHARE_PASSWORD';
GRANT ALL PRIVILEGES ON share_exchange.* TO 'share'@'%';
FLUSH PRIVILEGES;
SQL
```

### 2.3 部署应用
```bash
sudo mkdir -p /opt/share-exchange/{data/files,data/logs}
# 上传 jar（构建产物 share-app/target/share-exchange.jar）
scp share-exchange.jar zk@192.168.116.128:/tmp/
sudo mv /tmp/share-exchange.jar /opt/share-exchange/share-exchange.jar

# 环境变量（含 JWT/加密密钥、MySQL 连接、文件根目录）
sudo tee /etc/sysconfig/share-exchange >/dev/null <<'EOF'
SHARE_JWT_SECRET=<openssl rand -hex 32>
SHARE_CRYPTO_KEY=<openssl rand -hex 24>
SHARE_SEED_ENABLED=true
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DB=share_exchange
MYSQL_USER=share
MYSQL_PASSWORD=$MYSQL_SHARE_PASSWORD
SHARE_FILE_ROOT=/opt/share-exchange/data/files
EOF
sudo chmod 600 /etc/sysconfig/share-exchange

# systemd 单元
sudo tee /etc/systemd/system/share-exchange.service >/dev/null <<'EOF'
[Unit]
Description=Share Exchange Platform
After=network-online.target mysqld.service
Wants=network-online.target
[Service]
Type=simple
User=root
WorkingDirectory=/opt/share-exchange
EnvironmentFile=/etc/sysconfig/share-exchange
ExecStart=/usr/bin/java -Dfile.encoding=UTF-8 -Xms512m -Xmx3g -jar /opt/share-exchange/share-exchange.jar --spring.profiles.active=mysql
Restart=always
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=65535
[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload && sudo systemctl enable --now share-exchange
```

### 2.4 防火墙与 SELinux（演示环境）
```bash
sudo firewall-cmd --permanent --add-port=8080/tcp && sudo firewall-cmd --reload
sudo setenforce 0
sudo sed -i 's/^SELINUX=enforcing/SELINUX=permissive/' /etc/selinux/config
```

## 3. 验证结果

### 3.1 数据库建表与种子
- Hibernate `ddl-auto=update` 在 MySQL 8.4 建表 **61 张**，启动期 **0 条 DDL 错误**
- 种子：10 个用户 / 6 个角色 / 5 个数据源 / 9 个目录 / 3 个任务 / 3 个共享服务 / 6 条站内消息 / 3 条告警规则 / 72 小时指标

### 3.2 端到端冒烟（`node scripts/smoke-test.mjs`，`SHARE_BASE=http://192.168.116.128:8080`）
```
PASS=39  FAIL=0  SKIP=0  TOTAL=39
```
覆盖：登录与双因素、用户/组织/角色、数据源（含真实连通性测试）、目录（含敏感字段预览脱敏）、任务真实运行与实例/日志、质量规则报告、共享服务调试/文档/凭据、开放 API（AppKey 鉴权+动态脱敏）、申请审批、脱敏试算、文件上传与校验、审计、监控、通知、前置节点。

### 3.3 门户页面验收（Playwright）
截图见 [`docs/screenshots/`](screenshots/)：
- `01-dashboard.png` 工作台（真实统计）
- `02-catalog.png` 数据目录（检索 + 密级标签）
- `03-catalog-detail.png` 目录详情
- `04-approval.png` 申请审批
- `05-share-api.png` 共享服务
- `06-file-exchange.png` 文件交换
- `07-message.png` 消息中心

### 3.4 持久化与重启验证
- `systemctl restart share-exchange` 后健康检查 200、冒烟仍 **39/39**；
- MySQL 数据在重启后保持：`sys_user=10`、`cat_catalog=9`、`tk_task_instance=5`、`aud_operation_log=42`、`notify_message=15`（运行数据随操作累积，种子按 `count()==0` 判重不重复写入）；
- 说明生产数据库路径持久化正确，可支撑多轮演示与验收。

## 4. MySQL 适配修复记录（相对开发态 H2）

| 问题 | 现象 | 修复 |
| --- | --- | --- |
| 长 VARCHAR 行大小超限 | `cat_catalog_version` 建表报 `Row size too large (65535)` | `snapshot`、`summary`、`remark`、`jdbc_url`、`extra_config`、`change_log`、`error_msg`、`message` 等长字段统一改为 `columnDefinition="TEXT"` |
| MySQL 8.4 保留字 | `sensitive bit` 语法错误导致 `cat_catalog_item` / `apr_apply` 建表失败 | 列名改为 `is_sensitive`（Java 字段名不变，接口字段不变） |
| 表元数据查询硬编码 H2 schema | 共享 API 报“挂接数据表不存在” | `TableMetaCache` 查询改为 `table_schema='public' or table_schema=database()`，同时兼容 H2 与 MySQL |

> 以上修复已合并进主工程源码（`share-catalog`/`share-share`/`share-task`/`share-ops`/`share-agent` 实体），H2 与 MySQL 双环境均验证通过。

## 5. 运维命令

```bash
# 状态 / 日志
systemctl status share-exchange
journalctl -u share-exchange -f
tail -f /opt/share-exchange/data/logs/share-exchange.log

# 重启 / 停止
sudo systemctl restart share-exchange
sudo systemctl stop share-exchange

# 数据库
mysql -ushare -p"$MYSQL_SHARE_PASSWORD" -h127.0.0.1 share_exchange
# 健康检查
curl http://127.0.0.1:8080/api/health
```

## 6. 演示账号（门户 <http://192.168.116.128:8080/>）

| 账号 | 密码 | 角色 | 说明 |
| --- | --- | --- | --- |
| admin | 123456 | 系统管理员 | 需点“获取验证码”（演示环境自动填入） |
| provider | 123456 | 数据提供方 | 数据源/编目/响应申请 |
| consumer | 123456 | 数据使用方 | 浏览目录/提交申请/调用共享 API |
| security | 123456 | 安全管理员 | 分类分级/脱敏/审计（需短信码） |
| ops | 123456 | 交换运维 | 任务/调度/监控告警 |
| bizadmin | 123456 | 业务管理员 | 目录审核/审批 |

> 注意：生产环境必须修改所有演示口令、替换 `SHARE_JWT_SECRET`/`SHARE_CRYPTO_KEY`、关闭 `SHARE_SEED_ENABLED`、恢复 SELinux enforcing 并配置策略。
