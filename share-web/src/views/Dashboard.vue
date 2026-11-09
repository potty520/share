<template>
  <div v-loading="loading">
    <!-- 问候与快捷入口 -->
    <div class="welcome-card">
      <div>
        <h2>{{ greeting }}，{{ auth.user?.realName || auth.user?.username || '用户' }}</h2>
        <div class="muted mt-8">
          {{ today }} · {{ roleName }} · {{ auth.user?.deptName || '未分配部门' }}
        </div>
        <div class="muted mt-8" style="color: rgba(255, 255, 255, 0.75)">
          今日共 {{ fmtNumber(stats.todayExchange) }} 次数据交换，平台运行状态
          <el-tag size="small" :type="stats.platformOk ? 'success' : 'warning'" effect="dark">
            {{ stats.platformOk ? '正常' : '有告警' }}
          </el-tag>
        </div>
      </div>
      <div class="flex-gap">
        <el-button type="primary" plain @click="$router.push('/catalog')">查找数据</el-button>
        <el-button @click="$router.push('/approval')">我的申请</el-button>
      </div>
    </div>

    <!-- 指标卡 -->
    <div class="stat-grid stat-grid--4 mt-12">
      <div v-for="card in statCards" :key="card.label" class="stat-card">
        <div class="stat-label">{{ card.label }}</div>
        <div class="stat-value">{{ card.value }}</div>
        <div class="muted">{{ card.hint }}</div>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="page-card mt-12">
      <div class="page-header">
        <h3 class="section-title" style="margin: 0">快捷入口</h3>
        <span class="muted">按当前角色权限展示</span>
      </div>
      <div class="quick-grid">
        <div v-for="q in quickEntries" :key="q.path" class="quick-item" @click="$router.push(q.path)">
          <el-icon :size="22" :color="q.color"><component :is="q.icon" /></el-icon>
          <div class="quick-title">{{ q.title }}</div>
          <div class="muted">{{ q.desc }}</div>
        </div>
        <el-empty v-if="!quickEntries.length" description="暂无可用入口" :image-size="60" style="grid-column: 1 / -1" />
      </div>
    </div>

    <!-- 趋势图 -->
    <div class="chart-grid mt-12">
      <div class="page-card">
        <div class="page-header">
          <h3 class="section-title" style="margin: 0">交换量趋势</h3>
          <el-tag size="small" type="info">近 24 小时</el-tag>
        </div>
        <div v-if="exchangeTrend.labels.length" ref="exchangeChartRef" class="chart-box" />
        <el-empty v-else description="暂无交换量指标数据" :image-size="80" />
      </div>
      <div class="page-card">
        <div class="page-header">
          <h3 class="section-title" style="margin: 0">任务成功率趋势</h3>
          <el-tag size="small" type="info">按小时聚合</el-tag>
        </div>
        <div v-if="successTrend.labels.length" ref="successChartRef" class="chart-box" />
        <el-empty v-else description="暂无成功率指标数据" :image-size="80" />
      </div>
    </div>

    <!-- 最近实例 + 消息 -->
    <div class="chart-grid mt-12">
      <div class="page-card">
        <div class="page-header">
          <h3 class="section-title" style="margin: 0">最近任务实例</h3>
          <el-button text type="primary" @click="$router.push('/task')">全部任务</el-button>
        </div>
        <el-table :data="instances" size="small" stripe empty-text="暂无任务实例">
          <el-table-column prop="taskName" label="任务" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ pickName(row) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="行数" width="90" align="right">
            <template #default="{ row }">{{ fmtNumber(pick(row, ['rowCount', 'totalRows', 'rows'], '-')) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="90" align="right">
            <template #default="{ row }">{{ duration(row) }}</template>
          </el-table-column>
          <el-table-column label="开始时间" width="150">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['startTime', 'beginTime', 'createdAt'])) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="page-card">
        <div class="page-header">
          <h3 class="section-title" style="margin: 0">未读消息</h3>
          <el-button text type="primary" @click="$router.push('/message')">消息中心</el-button>
        </div>
        <el-empty v-if="!messages.length" description="暂无未读消息" :image-size="80" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="m in messages"
            :key="m.id"
            :timestamp="fmtDateTime(pick(m, ['createTime', 'createdAt', 'sendTime']))"
            placement="top"
            :type="m.readFlag || m.read ? 'info' : 'primary'"
          >
            <div class="flex-between">
              <span>{{ pick(m, ['title', 'subject'], '系统消息') }}</span>
              <el-tag v-if="m.type" size="small" effect="plain">{{ m.type }}</el-tag>
            </div>
            <div class="muted" style="margin-top: 4px">{{ pick(m, ['content', 'body'], '') }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import request from '@/api/request'
import { useAuthStore } from '@/store/auth'
import {
  autoResize,
  fmtDateTime,
  fmtNumber,
  listOf,
  numOf,
  pageOf,
  pick,
  tagType
} from '@/api/helpers'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const instances = ref([])
const messages = ref([])
const stats = reactive({
  catalogTotal: '-',
  catalogPublished: '-',
  sourceTotal: '-',
  taskTotal: '-',
  pendingApproval: '-',
  apiTotal: '-',
  alarmTotal: '-',
  nodeOnline: '-',
  todayExchange: 0,
  platformOk: false
})
const exchangeTrend = reactive({ labels: [], values: [] })
const successTrend = reactive({ labels: [], values: [] })
const exchangeChartRef = ref()
const successChartRef = ref()
let exchangeChart = null
let successChart = null
let disposeResize = () => {}

const roleNames = {
  ADMIN: '系统管理员',
  PROVIDER: '数据提供方',
  CONSUMER: '数据使用方',
  SECURITY: '安全管理员',
  OPS: '交换运维',
  BIZ_ADMIN: '业务管理员'
}
const roleName = computed(() => roleNames[auth.roleCode] || '用户')
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '凌晨好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })

const statCards = computed(() => [
  { label: '目录资源总数', value: fmtNumber(stats.catalogTotal), hint: `已发布 ${fmtNumber(stats.catalogPublished)}` },
  { label: '数据源', value: fmtNumber(stats.sourceTotal), hint: '已接入数据源' },
  { label: '交换任务', value: fmtNumber(stats.taskTotal), hint: '任务定义总数' },
  { label: '待办审批', value: fmtNumber(stats.pendingApproval), hint: '待我处理' },
  { label: '共享 API', value: fmtNumber(stats.apiTotal), hint: '共享服务总数' },
  { label: '活跃告警', value: fmtNumber(stats.alarmTotal), hint: '未确认告警' },
  { label: '在线节点', value: fmtNumber(stats.nodeOnline), hint: '前置交换节点' },
  { label: '今日交换量', value: fmtNumber(stats.todayExchange), hint: '批次/调用次数' }
])

const ALL_QUICK = [
  { title: '数据目录', desc: '检索并申请数据', path: '/catalog', perm: 'catalog:read', icon: 'Files', color: '#4a5ee5' },
  { title: '编目管理', desc: '维护目录与审核', path: '/catalog-manage', perm: 'catalog:write', icon: 'Tickets', color: '#e6a23c' },
  { title: '数据源', desc: '接入与连通测试', path: '/datasource', perm: 'datasource:read', icon: 'Coin', color: '#67c23a' },
  { title: '交换任务', desc: '调度与实例监控', path: '/task', perm: 'task:read', icon: 'Refresh', color: '#409eff' },
  { title: '申请审批', desc: '共享申请与授权', path: '/approval', perm: 'approval:apply', icon: 'Stamp', color: '#f56c6c' },
  { title: '共享服务', desc: 'API 上线与调试', path: '/share-api', perm: 'share:read', icon: 'Connection', color: '#7c4dff' },
  { title: '安全管控', desc: '分级与脱敏', path: '/security', perm: 'security:read', icon: 'Lock', color: '#ff9800' },
  { title: '文件交换', desc: '文件与传输任务', path: '/file', perm: 'file:read', icon: 'FolderOpened', color: '#00bcd4' },
  { title: '监控告警', desc: '平台运行态势', path: '/monitor', perm: 'monitor:read', icon: 'Monitor', color: '#3f51b5' },
  { title: '消息中心', desc: '通知与公告', path: '/message', perm: 'notify:read', icon: 'Bell', color: '#9c27b0' },
  { title: '用户权限', desc: '用户角色组织', path: '/user', perm: 'user:read', icon: 'User', color: '#607d8b' },
  { title: '安全审计', desc: '操作日志追溯', path: '/audit', perm: 'audit:read', icon: 'Document', color: '#795548' }
]
const quickEntries = computed(() => ALL_QUICK.filter((q) => auth.hasPerm(q.perm)))

const STATUS_TEXT = {
  SUCCESS: '成功',
  FAILED: '失败',
  RUNNING: '运行中',
  WAITING: '等待中',
  STOPPED: '已终止',
  RETRY: '重试中',
  PENDING: '待执行',
  SKIPPED: '已跳过'
}

function statusText(v) {
  return STATUS_TEXT[String(v || '').toUpperCase()] || v || '-'
}

function pickName(row) {
  return pick(row, ['taskName', 'task', 'name'], row?.taskId ? `任务 #${row.taskId}` : '-')
}

function duration(row) {
  const ms = pick(row, ['durationMs', 'elapsedMs', 'costMs'], null)
  if (ms !== null && ms !== '') {
    const n = numOf(ms)
    if (n >= 60000) return `${(n / 60000).toFixed(1)} 分`
    if (n >= 1000) return `${(n / 1000).toFixed(1)} 秒`
    return `${n} 毫秒`
  }
  const sec = pick(row, ['duration', 'elapsed', 'costSeconds'], null)
  if (sec !== null && sec !== '') return `${sec} 秒`
  return '-'
}

function renderCharts() {
  if (exchangeTrend.labels.length && exchangeChartRef.value) {
    if (!exchangeChart) exchangeChart = echarts.init(exchangeChartRef.value)
    exchangeChart.setOption({
      color: ['#4a5ee5'],
      tooltip: { trigger: 'axis' },
      grid: { left: 44, right: 16, top: 24, bottom: 28 },
      xAxis: { type: 'category', data: exchangeTrend.labels, axisLabel: { fontSize: 11 } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f7' } } },
      series: [
        {
          name: '交换量',
          type: 'line',
          smooth: true,
          showSymbol: false,
          areaStyle: { opacity: 0.15 },
          data: exchangeTrend.values
        }
      ]
    })
  }
  if (successTrend.labels.length && successChartRef.value) {
    if (!successChart) successChart = echarts.init(successChartRef.value)
    successChart.setOption({
      color: ['#67c23a'],
      tooltip: { trigger: 'axis', valueFormatter: (v) => `${Number(v).toFixed(2)}%` },
      grid: { left: 48, right: 16, top: 24, bottom: 28 },
      xAxis: { type: 'category', data: successTrend.labels, axisLabel: { fontSize: 11 } },
      yAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: '#eef1f7' } } },
      series: [
        {
          name: '成功率',
          type: 'line',
          smooth: true,
          showSymbol: false,
          areaStyle: { opacity: 0.12 },
          data: successTrend.values
        }
      ]
    })
  }
}

async function loadAll() {
  loading.value = true
  await Promise.allSettled([
    loadCatalogStats(),
    loadSourceTotal(),
    loadTaskTotal(),
    loadApprovalTotal(),
    loadApiTotal(),
    loadMonitorOverview(),
    loadMetrics(),
    loadAuditStats(),
    loadInstances(),
    loadMessages()
  ])
  loading.value = false
  await nextTick()
  renderCharts()
}

async function loadCatalogStats() {
  try {
    const res = await request.get('/catalog/v1/stats')
    const d = res.data || {}
    stats.catalogTotal = pick(d, ['total', 'catalogTotal', 'totalCount', 'count'], '-')
    stats.catalogPublished = pick(d, ['published', 'publishedCount', 'publishedTotal'], '-')
  } catch (e) {
    /* 未就绪 */
  }
}

async function loadSourceTotal() {
  try {
    const res = await request.get('/datasource/v1/sources', { params: { pageNum: 1, pageSize: 1 } })
    stats.sourceTotal = pageOf(res).total
  } catch (e) {
    /* 未就绪 */
  }
}

async function loadTaskTotal() {
  try {
    const res = await request.get('/task/v1/tasks', { params: { pageNum: 1, pageSize: 1 } })
    stats.taskTotal = pageOf(res).total
  } catch (e) {
    /* 未就绪 */
  }
}

async function loadApprovalTotal() {
  try {
    const res = await request.get('/approval/v1/applies', { params: { pageNum: 1, pageSize: 1, role: 'todo' } })
    stats.pendingApproval = pageOf(res).total
  } catch (e) {
    /* 未就绪 */
  }
}

async function loadApiTotal() {
  try {
    const res = await request.get('/share/v1/apis', { params: { pageNum: 1, pageSize: 1 } })
    stats.apiTotal = pageOf(res).total
  } catch (e) {
    /* 未就绪 */
  }
}

async function loadMonitorOverview() {
  try {
    const res = await request.get('/monitor/v1/overview')
    const d = res.data || {}
    stats.alarmTotal = pick(d, ['openAlarmCount', 'alarmCount', 'activeAlarms', 'unackAlarmCount'], '-')
    stats.nodeOnline = pick(d, ['nodeOnline', 'onlineNodes', 'onlineNodeCount'], '-')
    stats.platformOk = numOf(pick(d, ['openAlarmCount', 'alarmCount', 'activeAlarms'], 0)) === 0
    stats.todayExchange = pick(d, ['todayExchangeRows', 'todayExchange', 'exchangeCount'], 0)
  } catch (e) {
    /* 未就绪 */
  }
}

async function loadMetrics() {
  try {
    const res = await request.get('/monitor/v1/metrics', { params: { type: 'EXCHANGE' } })
    const d = res.data || {}
    const rows = listOf(d.points || d)
    exchangeTrend.labels = rows.map((r) => String(pick(r, ['time', 'date', 'hour', 'label'], '')))
    exchangeTrend.values = rows.map((r) => numOf(pick(r, ['value', 'count', 'amount'], 0)))
  } catch (e) {
    /* 未就绪 */
  }
  try {
    const res = await request.get('/audit/v1/stats')
    const d = res.data || {}
    const rows = listOf(d.dailyTrend || d.trend || d.daily)
    const labels = []
    const values = []
    rows.forEach((r) => {
      if (!r || typeof r !== 'object') return
      labels.push(String(pick(r, ['date', 'day', 'time', 'label'], '')))
      const total = numOf(pick(r, ['total', 'count', 'operationCount'], 0))
      const fail = numOf(pick(r, ['failCount', 'failed'], 0))
      values.push(total > 0 ? Number((((total - fail) / total) * 100).toFixed(2)) : 0)
    })
    if (labels.length) {
      successTrend.labels = labels
      successTrend.values = values
    }
  } catch (e) {
    /* 未就绪 */
  }
}

async function loadAuditStats() {
  try {
    const res = await request.get('/audit/v1/stats')
    const d = res.data || {}
    if (!successTrend.labels.length) {
      const rows = listOf(d.dailyTrend || [])
      if (rows.length) {
        successTrend.labels = rows.map((r) => String(pick(r, ['date', 'day', 'time', 'label'], '')))
        successTrend.values = rows.map(() => 0)
      }
    }
  } catch (e) {
    /* 未就绪 */
  }
}

async function loadInstances() {
  try {
    const res = await request.get('/task/v1/instances', { params: { pageNum: 1, pageSize: 5 } })
    instances.value = listOf(res.data).slice(0, 5)
  } catch (e) {
    /* 未就绪 */
  }
}

async function loadMessages() {
  try {
    const res = await request.get('/notify/v1/messages', { params: { pageNum: 1, pageSize: 5, unread: true } })
    messages.value = listOf(res.data).slice(0, 5)
  } catch (e) {
    /* 未就绪 */
  }
}

onMounted(async () => {
  await loadAll()
  const chs = [exchangeChart, successChart].filter(Boolean)
  if (chs.length) {
    const cleanups = chs.map((c) => autoResize(c))
    disposeResize = () => cleanups.forEach((fn) => fn())
  }
})

onBeforeUnmount(() => {
  disposeResize()
  if (exchangeChart) {
    exchangeChart.dispose()
    exchangeChart = null
  }
  if (successChart) {
    successChart.dispose()
    successChart = null
  }
})
</script>
