<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon :size="22"><Share /></el-icon>
        <span>共享交换系统</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        class="menu"
        background-color="#141c2f"
        text-color="#c5cde0"
        active-text-color="#ffffff"
      >
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.meta.icon || 'Menu'" /></el-icon>
          <span>{{ item.meta.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="crumb">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>{{ roleName }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ route.meta.title || '工作台' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="actions">
          <el-tooltip content="消息中心">
            <el-badge :value="unread" :hidden="!unread" class="msg-badge">
              <el-button text circle @click="$router.push('/message')">
                <el-icon :size="18"><Bell /></el-icon>
              </el-button>
            </el-badge>
          </el-tooltip>
          <el-dropdown @command="onCommand">
            <span class="user">
              <el-avatar :size="28">{{ (auth.user?.realName || 'U').slice(0, 1) }}</el-avatar>
              <span class="name">{{ auth.user?.realName || auth.user?.username || '未登录' }}</span>
              <el-tag size="small" effect="plain">{{ roleName }}</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人信息</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import request from '@/api/request'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const unread = ref(0)

const roleNames = {
  ADMIN: '系统管理员',
  PROVIDER: '数据提供方',
  CONSUMER: '数据使用方',
  SECURITY: '安全管理员',
  OPS: '交换运维',
  BIZ_ADMIN: '业务管理员'
}
const roleName = computed(() => roleNames[auth.roleCode] || '用户')

const menus = computed(() => {
  const layout = router.getRoutes().find((r) => r.path === '/')
  const children = (layout?.children || []).filter((r) => !r.meta?.hidden)
  // 后端 profile 返回菜单时优先按菜单路径过滤（兼容后端权限编码与前端不一致的情况）
  const remote = auth.menus || []
  if (remote.length) {
    const paths = new Set()
    const walk = (list) => {
      list.forEach((m) => {
        if (!m) return
        const raw = String(m.path || m.url || m.route || '')
        if (raw) paths.add(raw.replace(/^\//, '').split('?')[0])
        const sub = m.children || m.childList || []
        if (Array.isArray(sub) && sub.length) walk(sub)
      })
    }
    walk(remote)
    const matched = children.filter((r) => paths.has(r.path) || paths.has('/' + r.path))
    if (matched.length) return matched
  }
  return children.filter((r) => auth.hasPerm(r.meta?.perm))
})

const activeMenu = computed(() => {
  const path = route.path
  const match = menus.value.find((m) => path === '/' + m.path || path.startsWith('/' + m.path + '/'))
  return match ? '/' + match.path : path
})

async function loadUnread() {
  if (!auth.hasPerm('notify:read')) return
  try {
    const res = await request.get('/notify/v1/messages', { params: { pageNum: 1, pageSize: 1, unread: true } })
    unread.value = res.page?.total || 0
  } catch (e) {
    // 通知域未就绪时忽略
  }
}

function onCommand(cmd) {
  if (cmd === 'logout') {
    ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
      .then(async () => {
        await auth.logout()
        router.push('/login')
      })
      .catch(() => {})
  } else if (cmd === 'profile') {
    router.push('/message')
  }
}

onMounted(() => {
  loadUnread()
})
</script>

<style scoped>
.layout {
  height: 100%;
}
.aside {
  background: #141c2f;
  color: #fff;
  display: flex;
  flex-direction: column;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 18px;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  border-bottom: 1px solid #25304a;
}
.menu {
  border-right: none;
  flex: 1;
  overflow-y: auto;
}
.header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid var(--share-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.actions {
  display: flex;
  align-items: center;
  gap: 14px;
}
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.name {
  font-size: 13px;
}
.main {
  padding: 16px;
  overflow-y: auto;
}
.msg-badge {
  display: inline-flex;
}
</style>
