#!/bin/bash
# 运行状态巡检
# 口令通过环境变量传入：export MYSQL_SHARE_PASSWORD='...'
: "${MYSQL_SHARE_PASSWORD:?请先导出 MYSQL_SHARE_PASSWORD}"
MYSQL_USER=${MYSQL_USER:-share}
echo "=== 服务状态 ==="
systemctl is-active share-exchange
systemctl is-enabled share-exchange
echo "=== 版本 ==="
ls -lh /opt/share-exchange/share-exchange.jar | awk '{print $5, $9}'
echo "=== MySQL 数据量 ==="
mysql -u"$MYSQL_USER" -p"$MYSQL_SHARE_PASSWORD" -h127.0.0.1 share_exchange -N -e "select 'sys_user', count(*) from sys_user union all select 'cat_catalog', count(*) from cat_catalog union all select 'tk_task_instance', count(*) from tk_task_instance union all select 'aud_operation_log', count(*) from aud_operation_log union all select 'notify_message', count(*) from notify_message;" 2>/dev/null
echo "=== 门户/健康 ==="
curl -s -o /dev/null -w "portal_http=%{http_code}\n" http://127.0.0.1:8080/
curl -s http://127.0.0.1:8080/api/health
echo
echo "=== 资源 ==="
free -h | head -2
df -h /opt | tail -1
