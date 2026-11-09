#!/bin/bash
# 共享域排查：种子日志 + 演示表 + 健康检查
# 口令通过环境变量传入：export MYSQL_SHARE_PASSWORD='...'
: "${MYSQL_SHARE_PASSWORD:?请先导出 MYSQL_SHARE_PASSWORD}"
MYSQL_USER=${MYSQL_USER:-share}
echo "=== 共享域种子/错误日志 ==="
journalctl -u share-exchange --no-pager | grep -iE "shr_demo|演示业务表|ShareSeed|共享服务|share-share|share-seed" | tail -20
echo "=== MySQL 中 shr 开头的表 ==="
mysql -u"$MYSQL_USER" -p"$MYSQL_SHARE_PASSWORD" -h127.0.0.1 share_exchange -e "show tables like 'shr%';" 2>/dev/null
echo "=== 直接查一次 ==="
mysql -u"$MYSQL_USER" -p"$MYSQL_SHARE_PASSWORD" -h127.0.0.1 share_exchange -e "select count(*) from shr_demo_enterprise;" 2>&1 | tail -3
echo "=== 应用健康 ==="
curl -s http://127.0.0.1:8080/api/health
echo
