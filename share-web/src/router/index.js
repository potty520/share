import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '@/layout/MainLayout.vue'

const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '工作台', icon: 'HomeFilled', perm: '' } },
      { path: 'catalog', name: 'catalog', component: () => import('@/views/catalog/CatalogList.vue'), meta: { title: '数据目录', icon: 'Files', perm: 'catalog:read' } },
      { path: 'catalog/:id', name: 'catalog-detail', component: () => import('@/views/catalog/CatalogDetail.vue'), meta: { title: '目录详情', hidden: true, perm: 'catalog:read' } },
      { path: 'catalog-manage', name: 'catalog-manage', component: () => import('@/views/catalog/CatalogManage.vue'), meta: { title: '编目管理', icon: 'Tickets', perm: 'catalog:write' } },
      { path: 'datasource', name: 'datasource', component: () => import('@/views/datasource/DataSourceList.vue'), meta: { title: '数据源管理', icon: 'Coin', perm: 'datasource:read' } },
      { path: 'task', name: 'task', component: () => import('@/views/task/TaskList.vue'), meta: { title: '交换任务', icon: 'Refresh', perm: 'task:read' } },
      { path: 'task/create', name: 'task-create', component: () => import('@/views/task/TaskCreate.vue'), meta: { title: '新建交换任务', hidden: true, perm: 'task:write' } },
      { path: 'task/:id', name: 'task-detail', component: () => import('@/views/task/TaskDetail.vue'), meta: { title: '任务详情', hidden: true, perm: 'task:read' } },
      { path: 'quality', name: 'quality', component: () => import('@/views/task/Quality.vue'), meta: { title: '数据质量', icon: 'DataAnalysis', perm: 'quality:read' } },
      { path: 'approval', name: 'approval', component: () => import('@/views/approval/ApprovalList.vue'), meta: { title: '申请审批', icon: 'Stamp', perm: 'approval:apply' } },
      { path: 'share-api', name: 'share-api', component: () => import('@/views/share/ApiList.vue'), meta: { title: '共享服务', icon: 'Connection', perm: 'share:read' } },
      { path: 'share-api/:id', name: 'share-api-detail', component: () => import('@/views/share/ApiDetail.vue'), meta: { title: '服务详情', hidden: true, perm: 'share:read' } },
      { path: 'security', name: 'security', component: () => import('@/views/security/Security.vue'), meta: { title: '安全管控', icon: 'Lock', perm: 'security:read' } },
      { path: 'file', name: 'file', component: () => import('@/views/ops/FileExchange.vue'), meta: { title: '文件交换', icon: 'FolderOpened', perm: 'file:read' } },
      { path: 'audit', name: 'audit', component: () => import('@/views/ops/AuditLog.vue'), meta: { title: '安全审计', icon: 'Document', perm: 'audit:read' } },
      { path: 'monitor', name: 'monitor', component: () => import('@/views/ops/Monitor.vue'), meta: { title: '监控告警', icon: 'Monitor', perm: 'monitor:read' } },
      { path: 'agent', name: 'agent', component: () => import('@/views/ops/AgentNodes.vue'), meta: { title: '前置节点', icon: 'Cpu', perm: 'agent:read' } },
      { path: 'message', name: 'message', component: () => import('@/views/Message.vue'), meta: { title: '消息中心', icon: 'Bell', perm: 'notify:read' } },
      { path: 'user', name: 'user', component: () => import('@/views/system/UserManage.vue'), meta: { title: '用户与权限', icon: 'User', perm: 'user:read' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('share_token')
  document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '共享交换系统'
  if (to.meta.public) {
    next()
    return
  }
  if (!token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  next()
})

export default router
