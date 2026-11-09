#!/bin/bash
set -e
echo "=== 1. 安装 JDK 21 (headless) ==="
dnf -y install java-21-openjdk-headless >/tmp/jdk-install.log 2>&1 || { tail -20 /tmp/jdk-install.log; exit 1; }
java -version 2>&1 | head -2

echo "=== 2. 配置 MySQL 8.4 Community 仓库 ==="
cat > /etc/yum.repos.d/mysql-community.repo <<'EOF'
[mysql84-community]
name=MySQL 8.4 Community Server
baseurl=https://repo.mysql.com/yum/mysql-8.4-community/el/10/$basearch/
enabled=1
gpgcheck=0
module_hotfixes=1
EOF

echo "=== 3. 安装 mysql-community-server ==="
dnf -y install mysql-community-server >/tmp/mysql-install.log 2>&1 || { echo "MYSQL INSTALL FAILED"; tail -40 /tmp/mysql-install.log; exit 1; }
mysqld --version || true

echo "=== 4. 启动 MySQL ==="
systemctl enable --now mysqld
sleep 5
systemctl is-active mysqld
echo "=== 5. 完成 ==="
cat /var/log/mysql/mysqld.log 2>/dev/null | tail -5 || true
