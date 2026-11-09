#!/bin/bash
# 应用部署：SELinux/防火墙/环境变量/systemd 服务
# 数据库口令通过环境变量传入，禁止写死在仓库中：
#   export MYSQL_SHARE_PASSWORD='...'
set -e
: "${MYSQL_SHARE_PASSWORD:?请先导出 MYSQL_SHARE_PASSWORD}"
APP_DIR=/opt/share-exchange
mkdir -p "$APP_DIR/data/files" "$APP_DIR/data/logs"

echo "=== 1. SELinux ==="
SEL=$(getenforce)
echo "selinux=$SEL"
if [ "$SEL" = "Enforcing" ]; then
  # 演示环境：改为 permissive，避免为自定义目录编写 SELinux 策略（生产应使用 semanage 打标签）
  setenforce 0 || true
  sed -i 's/^SELINUX=enforcing/SELINUX=permissive/' /etc/selinux/config || true
  echo "switched to permissive"
fi

echo "=== 2. 防火墙放行 8080 ==="
if systemctl is-active --quiet firewalld; then
  firewall-cmd --permanent --add-port=8080/tcp >/dev/null 2>&1 || true
  firewall-cmd --reload >/dev/null 2>&1 || true
  echo "firewalld: 8080/tcp opened"
else
  echo "firewalld not active"
fi

echo "=== 3. 环境变量文件 ==="
if [ ! -f /etc/sysconfig/share-exchange ]; then
  JWT=$(openssl rand -hex 32)
  CRYPTO=$(openssl rand -hex 24)
  cat > /etc/sysconfig/share-exchange <<EOF
SHARE_JWT_SECRET=$JWT
SHARE_CRYPTO_KEY=$CRYPTO
SHARE_SEED_ENABLED=true
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DB=share_exchange
MYSQL_USER=share
MYSQL_PASSWORD=$MYSQL_SHARE_PASSWORD
SHARE_FILE_ROOT=/opt/share-exchange/data/files
EOF
  chmod 600 /etc/sysconfig/share-exchange
  echo "env file created"
else
  echo "env file exists"
fi

echo "=== 4. systemd 服务 ==="
cat > /etc/systemd/system/share-exchange.service <<'EOF'
[Unit]
Description=Share Exchange Platform
Documentation=file:/opt/share-exchange/README.md
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
systemctl daemon-reload
systemctl enable share-exchange >/dev/null 2>&1 || true
systemctl restart share-exchange
sleep 8
echo "=== 5. 服务状态 ==="
systemctl is-active share-exchange || true
journalctl -u share-exchange -n 15 --no-pager || true
echo "=== 6. 端口 ==="
ss -lntp | grep 8080 || echo "8080 not listening yet"
