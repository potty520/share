#!/bin/bash
# 环境探查（以 root 运行）
echo "=== 1. 配置免密 sudo ==="
echo 'zk ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/zk
chmod 440 /etc/sudoers.d/zk
echo SUDO_OK

echo "=== 2. 当前 dnf 仓库 ==="
dnf repolist 2>&1 | head -15

echo "=== 3. 镜像可达性 ==="
for h in mirrors.aliyun.com mirrors.tuna.tsinghua.edu.cn dl.rockylinux.org repo.mysql.com; do
  if curl -sI --max-time 8 "https://$h/" -o /dev/null; then echo "OK  $h"; else echo "FAIL $h"; fi
done

echo "=== 4. 软件包可用性 ==="
dnf list available mysql-server 2>&1 | tail -4
dnf list available mariadb-server 2>&1 | tail -4
dnf list available java-17-openjdk-headless 2>&1 | tail -3

echo "=== 5. 磁盘/服务 ==="
df -h / | tail -1
systemctl is-active firewalld 2>/dev/null || true
