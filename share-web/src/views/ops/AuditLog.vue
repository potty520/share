<template>
  <div class="page-card" v-loading="loading">
    <div class="page-header">
      <div>
        <h3 class="page-title">安全审计</h3>
        <div class="page-subtitle">操作审计检索、统计分析、数据流转追踪与异常行为识别</div>
      </div>
      <el-button :icon="Download" @click="exportCsv">导出审计报表</el-button>
    </div>

    <el-tabs v-model="tab" @tab-change="onTab">
      <!-- 操作日志 -->
      <el-tab-pane label="操作日志" name="logs">
        <el-form inline class="filter-bar" @submit.prevent>
          <el-form-item label="操作人">
            <el-input v-model="query.operator" placeholder="用户名 / 姓名" clearable style="width: 140px" @keyup.enter="search" />
          </el-form-item>
          <el-form-item label="动作">
            <el-input v-model="query.action" placeholder="如 LOGIN/CREATE" clearable style="width: 140px" @keyup.enter="search" />
          </el-form-item>
          <el-form-item label="模块">
            <el-input v-model="query.module" placeholder="如 catalog" clearable style="width: 130px" @keyup.enter="search" />
          </el-form-item>
          <el-form-item label="结果">
            <el-select v-model="query.result" clearable placeholder="全部" style="width: 110px">
              <el-option label="成功" value="SUCCESS" />
              <el-option label="失败" value="FAILED" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间范围">
            <el-date-picker
              v-model="timeRange"
              type="datetimerange"
              value-format="YYYY-MM-DDTHH:mm:ss"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              style="width: 340px"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="search">查询</el-button>
            <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="logs" size="small" stripe border empty-text="暂无审计日志">
          <el-table-column label="操作时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['operateTime', 'createTime'])) }}</template>
          </el-table-column>
          <el-table-column label="操作人" width="120">
            <template #default="{ row }">{{ pick(row, ['operatorName', 'operator'], '-') }}</template>
          </el-table-column>
          <el-table-column label="动作" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['action'], '-') }}</template>
          </el-table-column>
          <el-table-column label="模块" width="120">
            <template #default="{ row }">{{ pick(row, ['module'], '-') }}</template>
          </el-table-column>
          <el-table-column label="操作对象" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['targetType', 'targetId'], '-') }}{{ row.targetId ? '#' + row.targetId : '' }}</template>
          </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(pick(row, ['result', 'status'], 'SUCCESS'))">{{ resultText(pick(row, ['result', 'status'], 'SUCCESS')) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="IP" width="130">
            <template #default="{ row }">{{ pick(row, ['ip', 'clientIp', 'remoteIp'], '-') }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="90" align="right">
            <template #default="{ row }">{{ pick(row, ['costMs', 'duration', 'elapsed'], '-') }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="pageNum"
            v-model:page-size="pageSize"
            background
            layout="total, sizes, prev, pager, next"
            :total="total"
            :page-sizes="[10, 20, 50]"
            @current-change="loadLogs"
            @size-change="search"
          />
        </div>
      </el-tab-pane>

      <!-- 统计分析 -->
      <el-tab-pane label="统计分析" name="stats">
        <div class="stat-grid stat-grid--4 mb-12">
          <div class="stat-card">
            <div class="stat-label">操作总数</div>
            <div class="stat-value">{{ fmtNumber(pick(stats, ['total'], '-')) }}</div>
            <div class="muted">今日 {{ fmtNumber(pick(stats, ['todayCount'], 0)) }}</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">失败操作</div>
            <div class="stat-value">{{ fmtNumber(pick(stats, ['failCount'], '-')) }}</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">失败率</div>
            <div class="stat-value">{{ pick(stats, ['failRate'], '-') }}%</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">非工作时间操作</div>
            <div class="stat-value">{{ fmtNumber(pick(stats, ['offHoursCount'], '-')) }}</div>
          </div>
        </div>
        <div class="chart-grid">
          <div>
            <h4 class="section-title">操作量日趋势</h4>
            <div v-if="trend.labels.length" ref="trendChartRef" class="chart-box" style="height: 300px" />
            <el-empty v-else description="暂无趋势数据" :image-size="80" />
          </div>
          <div>
            <h4 class="section-title">按模块统计</h4>
            <div v-if="moduleDist.length" ref="moduleChartRef" class="chart-box" style="height: 300px" />
            <el-empty v-else description="暂无模块统计数据" :image-size="80" />
          </div>
        </div>
      </el-tab-pane>

      <!-- 流转追踪 -->
      <el-tab-pane label="数据流转追踪" name="trace">
        <el-form inline class="filter-bar" @submit.prevent>
          <el-form-item label="批次号">
            <el-input v-model="batchId" placeholder="如 B20260101-0001" style="width: 260px" @keyup.enter="loadTrace" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" :loading="tracing" @click="loadTrace">追踪</el-button>
          </el-form-item>
        </el-form>
        <el-empty v-if="!traceNodes.length && !tracing" description="输入批次号查询 源 → 任务 → 目标 的完整链路" :image-size="90" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="(n, i) in traceNodes"
            :key="i"
            :timestamp="fmtDateTime(pick(n, ['time', 'createTime', 'operateTime']))"
            :type="i === 0 ? 'primary' : i === traceNodes.length - 1 ? 'success' : 'warning'"
            placement="top"
          >
            <div class="flex-between">
              <strong>{{ traceTitle(n, i) }}</strong>
              <el-tag size="small" :type="tagType(pick(n, ['result', 'status'], 'SUCCESS'))">{{ traceStatus(n) }}</el-tag>
            </div>
            <div class="muted" style="margin-top: 4px">{{ traceDetail(n) }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-descriptions v-if="traceInfo && Object.keys(traceInfo).length" :column="3" border size="small" class="mt-12">
          <el-descriptions-item label="批次号">{{ pick(traceInfo, ['batchId'], batchId) }}</el-descriptions-item>
          <el-descriptions-item label="任务">{{ pick(traceInfo, ['taskName', 'taskId'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="行数">{{ fmtNumber(pick(traceInfo, ['rowCount', 'totalRows'], '-')) }}</el-descriptions-item>
          <el-descriptions-item label="源端">{{ pick(traceInfo, ['sourceName', 'source'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="目标端">{{ pick(traceInfo, ['targetName', 'target'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ pick(traceInfo, ['status'], '-') }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <!-- 异常行为 -->
      <el-tab-pane label="异常行为" name="anomalies">
        <el-table :data="anomalies" size="small" stripe border empty-text="暂无异常行为">
          <el-table-column label="异常类型" width="160">
            <template #default="{ row }">{{ pick(row, ['title', 'type'], '-') }}</template>
          </el-table-column>
          <el-table-column label="风险级别" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="riskTag(row.level)">{{ riskText(row.level) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="涉及用户" width="130">
            <template #default="{ row }">{{ pick(row, ['operatorName', 'userName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="描述" min-width="280" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['description', 'message', 'detail'], '-') }}</template>
          </el-table-column>
          <el-table-column label="次数" width="90" align="right">
            <template #default="{ row }">{{ pick(row, ['count', 'triggerCount'], '-') }}</template>
          </el-table-column>
          <el-table-column label="发生时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['firstTime', 'lastTime', 'createTime'])) }}</template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="anomalyPage"
            :page-size="10"
            background
            layout="total, prev, pager, next"
            :total="anomalyTotal"
            @current-change="loadAnomalies"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 日志详情 -->
    <el-drawer v-model="detailVisible" title="审计日志详情" size="640px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item v-for="(v, k) in detailRow" :key="k" :label="fieldLabel(k)">{{ valueText(v) }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, RefreshLeft, Download } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import request from '@/api/request'
import { autoResize, clean, fmtDateTime, fmtNumber, listOf, numOf, pageOf, pick, saveBlob, tagType } from '@/api/helpers'

const loading = ref(false)
const tab = ref('logs')
const logs = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const query = reactive({ operator: '', action: '', module: '', result: '' })
const timeRange = ref([])
const detailVisible = ref(false)
const detailRow = ref({})

const stats = ref({})
const trend = reactive({ labels: [], values: [] })
const moduleDist = ref([])
const trendChartRef = ref()
const moduleChartRef = ref()
let trendChart = null
let moduleChart = null
let disposeResize = () => {}

const batchId = ref('')
const tracing = ref(false)
const traceNodes = ref([])
const traceInfo = ref({})

const anomalies = ref([])
const anomalyPage = ref(1)
const anomalyTotal = ref(0)

const FIELD_LABELS = {
  id: '日志ID',
  operator: '操作人',
  operatorId: '操作人ID',
  operatorName: '操作人姓名',
  roleCode: '角色',
  action: '动作',
  module: '模块',
  target: '操作对象',
  targetType: '对象类型',
  targetId: '对象ID',
  result: '结果',
  status: '状态',
  ip: '客户端 IP',
  costMs: '耗时(ms)',
  operateTime: '操作时间',
  createTime: '创建时间',
  method: '请求方法',
  uri: '请求地址',
  detail: '详情',
  detailPreview: '详情摘要',
  errorMsg: '错误信息',
  userAgent: '客户端',
  traceId: '链路追踪ID',
  beforeData: '变更前',
  afterData: '变更后'
}

function resultText(v) {
  const s = String(v || 'SUCCESS').toUpperCase()
  return s === 'SUCCESS' ? '成功' : s === 'FAILED' ? '失败' : v
}

function riskText(v) {
  const s = String(v ?? '').toUpperCase()
  if (['HIGH', '1', 'CRITICAL'].includes(s)) return '高'
  if (['MEDIUM', '2', 'WARN'].includes(s)) return '中'
  return '低'
}

function riskTag(v) {
  const s = String(v ?? '').toUpperCase()
  if (['HIGH', '1', 'CRITICAL'].includes(s)) return 'danger'
  if (['MEDIUM', '2', 'WARN'].includes(s)) return 'warning'
  return 'info'
}

function fieldLabel(k) {
  return FIELD_LABELS[k] || k
}

function valueText(v) {
  if (v === null || v === undefined || v === '') return '-'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

function traceTitle(n, i) {
  return pick(n, ['title', 'name', 'nodeName', 'stage'], i === 0 ? '源端' : i === traceNodes.value.length - 1 ? '目标端' : '处理环节')
}

function traceStatus(n) {
  return resultText(pick(n, ['result', 'status'], 'SUCCESS'))
}

function traceDetail(n) {
  const target = pick(n, ['target', 'targetName', 'description', 'message', 'detail'], '')
  const rows = pick(n, ['rowCount', 'count'], '')
  const parts = []
  if (target) parts.push(String(target))
  if (rows !== '') parts.push(`行数：${rows}`)
  return parts.join(' · ') || '无更多信息'
}

async function loadLogs() {
  loading.value = true
  try {
    const params = clean({
      ...query,
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      from: timeRange.value?.[0],
      to: timeRange.value?.[1]
    })
    const res = await request.get('/audit/v1/logs', { params })
    logs.value = listOf(res.data)
    total.value = pageOf(res).total
  } catch (e) {
    logs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  pageNum.value = 1
  loadLogs()
}

function reset() {
  query.operator = ''
  query.action = ''
  query.module = ''
  query.result = ''
  timeRange.value = []
  search()
}

function openDetail(row) {
  detailRow.value = row
  detailVisible.value = true
}

async function exportCsv() {
  try {
    const blob = await request.get('/audit/v1/export', {
      responseType: 'blob',
      params: clean({
        ...query,
        from: timeRange.value?.[0],
        to: timeRange.value?.[1]
      })
    })
    saveBlob(blob, `audit-${Date.now()}.csv`)
    ElMessage.success('审计报表已开始下载')
  } catch (e) {
    ElMessage.error('导出失败，请确认审计导出接口已就绪')
  }
}

async function loadStats() {
  try {
    const res = await request.get('/audit/v1/stats', { params: { days: 7 } })
    stats.value = res.data || {}
    const rows = listOf(stats.value.dailyTrend || [])
    trend.labels = rows.map((r) => String(pick(r, ['date', 'day', 'label'], '')))
    trend.values = rows.map((r) => numOf(pick(r, ['count', 'total'], 0)))
    const byModule = listOf(stats.value.byModule || [])
    moduleDist.value = byModule.map((m) => ({
      name: pick(m, ['name', 'module', 'label'], '-'),
      value: numOf(pick(m, ['value', 'count', 'total'], 0))
    }))
  } catch (e) {
    stats.value = {}
    trend.labels = []
    trend.values = []
    moduleDist.value = []
  }
}

function renderCharts() {
  if (trend.labels.length && trendChartRef.value) {
    if (!trendChart) trendChart = echarts.init(trendChartRef.value)
    trendChart.setOption({
      color: ['#4a5ee5'],
      tooltip: { trigger: 'axis' },
      grid: { left: 48, right: 16, top: 20, bottom: 30 },
      xAxis: { type: 'category', data: trend.labels, axisLabel: { fontSize: 11 } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f7' } } },
      series: [{ name: '操作数', type: 'line', smooth: true, showSymbol: false, areaStyle: { opacity: 0.14 }, data: trend.values }]
    })
  }
  if (moduleDist.value.length && moduleChartRef.value) {
    if (!moduleChart) moduleChart = echarts.init(moduleChartRef.value)
    moduleChart.setOption({
      color: ['#6f86ff', '#4a5ee5', '#e6a23c', '#67c23a', '#f56c6c', '#909399'],
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, type: 'scroll' },
      series: [
        {
          name: '模块操作数',
          type: 'pie',
          radius: ['38%', '64%'],
          center: ['50%', '44%'],
          data: moduleDist.value
        }
      ]
    })
  }
}

async function loadTrace() {
  if (!batchId.value) {
    ElMessage.warning('请输入批次号')
    return
  }
  tracing.value = true
  traceNodes.value = []
  traceInfo.value = {}
  try {
    const res = await request.get('/audit/v1/trace', { params: { batchId: batchId.value } })
    const d = res.data || {}
    traceInfo.value = d.batch ? { ...d.batch, batchId: d.batchId || batchId.value } : d
    const chain = listOf(d.chain || [])
    traceNodes.value = chain.length
      ? chain
      : [...listOf(d.transferLogs || []).map((x) => ({ stage: x.stage || '传输日志', ...x })), ...listOf(d.auditLogs || []).map((x) => ({ stage: '审计日志', ...x }))]
    if (!traceNodes.value.length) ElMessage.info('未查询到该批次的流转记录')
  } catch (e) {
    traceNodes.value = []
  } finally {
    tracing.value = false
  }
}

async function loadAnomalies() {
  try {
    const res = await request.get('/audit/v1/anomalies')
    const d = res.data
    anomalies.value = listOf(d?.items || d?.list || d)
    anomalyTotal.value = anomalies.value.length
  } catch (e) {
    anomalies.value = []
    anomalyTotal.value = 0
  }
}

function onTab(name) {
  if (name === 'logs') loadLogs()
  if (name === 'stats') {
    loadStats().then(() => nextTick().then(renderCharts))
  }
  if (name === 'anomalies') loadAnomalies()
}

onMounted(async () => {
  await loadLogs()
  await (async () => {
    await loadStats()
    await nextTick()
    renderCharts()
  })()
  const cleanups = [trendChart, moduleChart].filter(Boolean).map((c) => autoResize(c))
  disposeResize = () => cleanups.forEach((fn) => fn())
})

onBeforeUnmount(() => {
  disposeResize()
  if (trendChart) {
    trendChart.dispose()
    trendChart = null
  }
  if (moduleChart) {
    moduleChart.dispose()
    moduleChart = null
  }
})
</script>
