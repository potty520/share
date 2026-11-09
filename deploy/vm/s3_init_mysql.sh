#!/bin/bash
# MySQL 初始化：设置 root 口令、创建业务库与账号
# 口令通过环境变量传入，禁止写死在仓库中：
#   export MYSQL_ROOT_PASSWORD='...' MYSQL_SHARE_PASSWORD='...'
set -e
: "${MYSQL_ROOT_PASSWORD:?请先导出 MYSQL_ROOT_PASSWORD}"
: "${MYSQL_SHARE_PASSWORD:?请先导出 MYSQL_SHARE_PASSWORD}"

LOG=$(ls /var/log/mysql/mysqld.log /var/log/mysqld.log 2>/dev/null | head -1)
echo "log=$LOG"
TMP_PWD=$(grep -i 'temporary password' "$LOG" 2>/dev/null | tail -1 | sed 's/.*root@localhost: //')
ROOT_PWD="$MYSQL_ROOT_PASSWORD"
if [ -n "$TMP_PWD" ]; then
  echo "got temporary root password"
  mysql --connect-expired-password -uroot -p"$TMP_PWD" -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '$ROOT_PWD';" || true
else
  echo "no temporary password found (already initialized?)"
fi

mysql -uroot -p"$ROOT_PWD" <<SQL
CREATE DATABASE IF NOT EXISTS share_exchange DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'share'@'%' IDENTIFIED BY '$MYSQL_SHARE_PASSWORD';
ALTER USER 'share'@'%' IDENTIFIED BY '$MYSQL_SHARE_PASSWORD';
GRANT ALL PRIVILEGES ON share_exchange.* TO 'share'@'%';
FLUSH PRIVILEGES;
SELECT VERSION() AS mysql_version;
SQL
echo "MYSQL_INIT_OK"
