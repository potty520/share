<template>
  <div v-loading="loading">
    <!-- 运维大屏 -->
    <div class="screen">
      <div class="flex-between mb-12">
        <div>
          <h3 class="screen-title" style="font-size: 18px; color: #fff">平台运行态势</h3>
          <span class="muted">数据刷新时间：{{ refreshTime }} · 每 60 秒自动刷新</span>
        </div>
        <div class="flex-gap">
          <el-tag :type="alarmCount > 0 ? 'danger' : 'success'" effect="dark">
            {{ alarmCount > 0 ? `存在 ${alarmCount} 条未确认告警` : '运行正常' }}
          </el-tag>
          <el-button size="small" :icon="Refresh" @click="loadAll">立即刷新</el-button>
        </div>
      </div>

      <div class="stat-grid">
        <div class="stat-card">
          <div class="stat-label">在线节点</div>
          <div class="stat-value">{{ fmtNumber(pick(overview, ['nodeOnline'], '-')) }}</div>
          <div class="muted">共 {{ fmtNumber(pick(overview, ['nodeTotal'], '-')) }} 个前置节点</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">任务成功率</div>
          <div class="stat-value">{{ rateText(pick(overview, ['taskSuccessRate'], '-')) }}</div>
          <div class="muted">成功 {{ fmtNumber(pick(overview, ['taskSuccess'], 0)) }} / 失败 {{ fmtNumber(pick(overview, ['taskFail'], 0)) }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">今日 API 调用</div>
          <div class="stat-value">{{ fmtNumber(pick(overview, ['todayApiCalls'], '-')) }}</div>
          <div class="muted">共享服务开放调用</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">活跃告警</div>
          <div class="stat-value">{{ fmtNumber(alarmCount) }}</div>
          <div class="muted">待确认</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">今日交换行数</div>
          <div class="stat-value">{{ fmtNumber(pick(overview, ['todayExchangeRows'], '-')) }}</div>
          <div class="muted">数据交换总量</div>
        </div>
      </div>

      <div class="chart-grid mt-12">
        <div>
          <h4 class="screen-title">交换量趋势</h4>
          <div v-if="exchangeTrend.labels.length" ref="exchangeChartRef" class="chart-box" />
          <el-empty v-else description="暂无交换量指标" :image-size="70" />
        </div>
        <div>
          <h4 class="screen-title">成功率趋势</h4>
          <div v-if="successTrend.labels.length" ref="successChartRef" class="chart-box" />
          <el-empty v-else description="暂无成功率指标" :image-size="70" />
        </div>
        <div>
          <h4 class="screen-title">节点资源负载（CPU %）</h4>
          <div v-if="nodes.length" ref="nodeChartRef" class="chart-box" />
          <el-empty v-else description="暂无节点数据" :image-size="70" />
        </div>
        <div>
          <h4 class="screen-title">部门共享排行（行数）</h4>
          <div v-if="deptRank.length" ref="deptChartRef" class="chart-box" />
          <el-empty v-else description="暂无部门排行数据" :image-size="70" />
        </div>
      </div>
    </div>

    <!-- 告警与报表 -->
    <div class="page-card mt-12">
      <div class="page-header">
        <h3 class="page-title">告警与运维报表</h3>
        <el-radio-group v-model="period" size="small" @change="loadReports">
          <el-radio-button value="day">日报</el-radio-button>
          <el-radio-button value="week">周报</el-radio-button>
          <el-radio-button value="month">月报</el-radio-button>
        </el-radio-group>
      </div>

      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane label="告警记录" name="alarms">
          <div class="flex-between mb-12">
            <span class="muted">按规则阈值触发，确认后从活跃告警中移除</span>
            <el-button size="small" :icon="Refresh" @click="loadAlarms">刷新</el-button>
          </div>
          <el-table :data="alarms" size="small" stripe border empty-text="暂无告警记录">
            <el-table-column label="告警时间" width="160">
              <template #default="{ row }">{{ fmtDateTime(pick(row, ['firstTime', 'createTime', 'lastTime'])) }}</template>
            </el-table-column>
            <el-table-column label="规则" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ pick(row, ['ruleName', 'title'], '-') }}</template>
            </el-table-column>
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="levelTag(pick(row, ['level', 'alarmLevel']))">{{ levelText(pick(row, ['level', 'alarmLevel'])) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="告警内容" min-width="230" show-overflow-tooltip>
              <template #default="{ row }">{{ pick(row, ['content', 'message', 'description'], '-') }}</template>
            </el-table-column>
            <el-table-column label="指标值 / 阈值" width="150" align="center">
              <template #default="{ row }">{{ pick(row, ['metricValue'], '-') }} / {{ pick(row, ['thresholdValue'], '-') }}</template>
            </el-table-column>
            <el-table-column label="次数" width="80" align="right">
              <template #default="{ row }">{{ pick(row, ['triggerCount'], '-') }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="ackStatusTag(row)">{{ ackStatusText(row) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="isAcked(row)" @click="ack(row)">确认</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination
              v-model:current-page="alarmPage"
              :page-size="10"
              background
              layout="total, prev, pager, next"
              :total="alarmTotal"
              @current-change="loadAlarms"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="告警规则" name="rules">
          <div class="flex-between mb-12">
            <span class="muted">配置阈值、持续时长、级别与通知渠道</span>
            <el-button size="small" type="primary" :icon="Plus" @click="openRule()">新增规则</el-button>
          </div>
          <el-table :data="rules" size="small" stripe border empty-text="暂无告警规则">
            <el-table-column label="规则名称" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ pick(row, ['ruleName', 'name'], '-') }}</template>
            </el-table-column>
            <el-table-column label="监控指标" width="150">
              <template #default="{ row }">{{ metricText(pick(row, ['metricType', 'metric'])) }}</template>
            </el-table-column>
            <el-table-column label="条件" width="140">
              <template #default="{ row }">{{ pick(row, ['operator'], '') }} {{ pick(row, ['threshold'], '') }}</template>
            </el-table-column>
            <el-table-column label="持续" width="110">
              <template #default="{ row }">{{ durationText(row) }}</template>
            </el-table-column>
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="levelTag(pick(row, ['level', 'alarmLevel']))">{{ levelText(pick(row, ['level', 'alarmLevel'])) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="通知渠道" min-width="130" show-overflow-tooltip>
              <template #default="{ row }">{{ pick(row, ['channels'], '-') }}</template>
            </el-table-column>
            <el-table-column label="启用" width="90">
              <template #default="{ row }">
                <el-switch :model-value="row.enabled !== false" @change="(v) => toggleRule(row, v)" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRule(row)">编辑</el-button>
                <el-button link type="danger" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="运维报表" name="reports">
          <el-descriptions :column="3" border size="small" class="mb-12">
            <el-descriptions-item label="统计周期">{{ periodText }}</el-descriptions-item>
            <el-descriptions-item label="交换行数">{{ fmtNumber(exchangeRows) }}</el-descriptions-item>
            <el-descriptions-item label="交换批次数">{{ fmtNumber(exchangeBatches) }}</el-descriptions-item>
            <el-descriptions-item label="交换成功率">{{ rateText(exchangeRate) }}</el-descriptions-item>
            <el-descriptions-item label="平均耗时">{{ pick(reportExchange, ['avgCostMs'], '-') }} ms</el-descriptions-item>
            <el-descriptions-item label="在线节点">{{ pick(reportNode, ['online'], '-') }} / {{ pick(reportNode, ['total'], '-') }}</el-descriptions-item>
          </el-descriptions>
          <div class="chart-grid">
            <div>
              <h4 class="section-title">部门共享排行</h4>
              <el-table :data="deptRank" size="small" border stripe empty-text="暂无部门排行数据">
                <el-table-column label="部门" min-width="160">
                  <template #default="{ row }">{{ pick(row, ['deptName'], '-') }}</template>
                </el-table-column>
                <el-table-column label="交换行数" width="120" align="right">
                  <template #default="{ row }">{{ fmtNumber(pick(row, ['rowCount'], 0)) }}</template>
                </el-table-column>
                <el-table-column label="批次数" width="100" align="right">
                  <template #default="{ row }">{{ fmtNumber(pick(row, ['batchCount'], 0)) }}</template>
                </el-table-column>
                <el-table-column label="成功率" width="100" align="right">
                  <template #default="{ row }">{{ rateText(pick(row, ['successRate'], 0)) }}</template>
                </el-table-column>
              </el-table>
            </div>
            <div>
              <h4 class="section-title">任务健康度</h4>
              <el-table :data="taskHealth" size="small" border stripe empty-text="暂无任务健康度数据">
                <el-table-column label="任务" min-width="170" show-overflow-tooltip>
                  <template #default="{ row }">{{ pick(row, ['taskName', 'name'], '-') }}</template>
                </el-table-column>
                <el-table-column label="成功率" width="110" align="right">
                  <template #default="{ row }">{{ rateText(pick(row, ['successRate', 'healthScore'], 0)) }}</template>
                </el-table-column>
                <el-table-column label="评级" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.grade === 'A' ? 'success' : row.grade === 'B' ? 'warning' : 'danger'">{{ pick(row, ['grade'], '-') }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="批次数" width="90" align="right">
                  <template #default="{ row }">{{ pick(row, ['batchCount'], '-') }}</template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 告警规则编辑 -->
    <el-dialog v-model="ruleVisible" :title="ruleForm.id ? '编辑告警规则' : '新增告警规则'" width="620px" append-to-body>
      <el-form ref="ruleRef" :model="ruleForm" :rules="ruleRules" label-width="100px">
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="ruleForm.ruleName" placeholder="如：任务失败告警" />
        </el-form-item>
        <el-form-item label="监控指标">
          <el-select v-model="ruleForm.metricType" filterable allow-create style="width: 100%">
            <el-option label="任务失败数 TASK_FAIL" value="TASK_FAIL" />
            <el-option label="节点离线 NODE_OFFLINE" value="NODE_OFFLINE" />
            <el-option label="API 错误率 API_ERROR_RATE" value="API_ERROR_RATE" />
            <el-option label="节点 CPU 使用率 NODE_CPU" value="NODE_CPU" />
            <el-option label="节点内存使用率 NODE_MEM" value="NODE_MEM" />
            <el-option label="磁盘使用率 NODE_DISK" value="NODE_DISK" />
            <el-option label="任务耗时 TASK_DURATION" value="TASK_DURATION" />
          </el-select>
        </el-form-item>
        <el-form-item label="条件">
          <el-select v-model="ruleForm.operator" style="width: 130px; margin-right: 8px">
            <el-option label="大于 >" value=">" />
            <el-option label="大于等于 >=" value=">=" />
            <el-option label="小于 <" value="<" />
            <el-option label="小于等于 <=" value="<=" />
            <el-option label="等于 ==" value="==" />
          </el-select>
          <el-input-number v-model="ruleForm.threshold" :min="0" :step="1" style="flex: 1" />
        </el-form-item>
        <el-form-item label="持续时长">
          <el-input-number v-model="ruleForm.durationSeconds" :min="0" :step="60" style="width: 200px" />
          <span class="muted" style="margin-left: 8px">秒</span>
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="ruleForm.alarmLevel" style="width: 100%">
            <el-option label="P0 严重" value="P0" />
            <el-option label="P1 高" value="P1" />
            <el-option label="P2 中" value="P2" />
            <el-option label="P3 低" value="P3" />
          </el-select>
        </el-form-item>
        <el-form-item label="通知渠道">
          <el-input v-model="ruleForm.channels" placeholder="如：INBOX,EMAIL,WEBHOOK" />
        </el-form-item>
        <el-form-item label="对象编码">
          <el-input v-model="ruleForm.targetCode" placeholder="可选，节点编码 / 任务编码" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.enabled" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="ruleForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRule" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Plus } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import request from '@/api/request'
import { autoResize, confirmBox, fmtDateTime, fmtNumber, listOf, numOf, pick, tagType } from '@/api/helpers'

const loading = ref(false)
const overview = ref({})
const nodes = ref([])
const exchangeTrend = reactive({ labels: [], values: [] })
const successTrend = reactive({ labels: [], values: [] })
const deptRank = ref([])
const taskHealth = ref([])
const alarmCount = ref(0)
const refreshTime = ref('-')
const period = ref('day')
const tab = ref('alarms')

const alarms = ref([])
const alarmPage = ref(1)
const alarmTotal = ref(0)
const rules = ref([])
const ruleVisible = ref(false)
const savingRule = ref(false)
const ruleRef = ref()
const ruleForm = reactive(emptyRule())
const ruleRules = { ruleName: [{ required: true, message: '请输入规则名称', trigger: 'blur' }] }

const exchangeChartRef = ref()
const successChartRef = ref()
const nodeChartRef = ref()
const deptChartRef = ref()
let exchangeChart = null
let successChart = null
let nodeChart = null
let deptChart = null
let disposeResize = () => {}
let timer = null

const reportExchange = ref({})
const reportNode = ref({})
const exchangeRows = computed(() => pick(reportExchange.value, ['rows'], '-'))
const exchangeBatches = computed(() => pick(reportExchange.value, ['batchCount'], '-'))
const exchangeRate = computed(() => pick(reportExchange.value, ['successRate'], '-'))

const LEVEL = { P0: 'P0 严重', P1: 'P1 高', P2: 'P2 中', P3: 'P3 低', HIGH: '高', MEDIUM: '中', LOW: '低' }
const METRIC = {
  TASK_FAIL: '任务失败数',
  NODE_OFFLINE: '节点离线',
  API_ERROR_RATE: 'API 错误率',
  NODE_CPU: '节点 CPU 使用率',
  NODE_MEM: '节点内存使用率',
  NODE_DISK: '磁盘使用率',
  TASK_DURATION: '任务耗时',
  EXCHANGE: '交换量'
}
const PERIOD_TEXT = { day: '日报（近 24 小时）', week: '周报（近 7 天）', month: '月报（近 30 天）' }

const periodText = computed(() => PERIOD_TEXT[period.value] || '-')

function emptyRule() {
  return {
    id: null,
    ruleName: '',
    metricType: 'TASK_FAIL',
    operator: '>',
    threshold: 0,
    durationSeconds: 300,
    alarmLevel: 'P1',
    channels: 'INBOX',
    targetCode: '',
    enabled: true,
    description: ''
  }
}

function levelText(v) {
  return LEVEL[String(v ?? '').toUpperCase()] || v || '-'
}

function levelTag(v) {
  const s = String(v ?? '').toUpperCase()
  if (['P0', 'HIGH', 'CRITICAL'].includes(s)) return 'danger'
  if (['P1', 'MEDIUM', 'WARN'].includes(s)) return 'warning'
  return 'info'
}

function metricText(v) {
  const s = String(v || '').toUpperCase()
  return METRIC[s] || v || '-'
}

function durationText(row) {
  const sec = row.durationSeconds
  if (sec === undefined || sec === null) return '-'
  const n = numOf(sec)
  if (n >= 3600) return `${(n / 3600).toFixed(1)} 小时`
  if (n >= 60) return `${Math.round(n / 60)} 分钟`
  return `${n} 秒`
}

function rateText(v) {
  if (v === null || v === undefined || v === '' || v === '-') return '-'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return `${(n <= 1 && n > 0 ? n * 100 : n).toFixed(2)}%`
}

function isAcked(row) {
  const s = String(row.status || '').toUpperCase()
  return ['ACKED', 'CLOSED', 'DONE'].includes(s)
}

function ackStatusText(row) {
  const s = String(row.status || '').toUpperCase()
  if (s === 'OPEN') return '未确认'
  if (s === 'ACKED') return '已确认'
  if (s === 'CLOSED') return '已关闭'
  return s || '-'
}

function ackStatusTag(row) {
  const s = String(row.status || '').toUpperCase()
  if (s === 'OPEN') return 'danger'
  if (s === 'ACKED') return 'warning'
  return 'success'
}

async function loadOverview() {
  try {
    const res = await request.get('/monitor/v1/overview')
    overview.value = res.data || {}
    alarmCount.value = numOf(pick(overview.value, ['openAlarmCount', 'alarmCount'], 0))
  } catch (e) {
    overview.value = {}
  }
}

async function loadNodes() {
  try {
    const res = await request.get('/monitor/v1/nodes')
    nodes.value = listOf(res.data)
  } catch (e) {
    nodes.value = []
  }
}

async function loadMetrics() {
  try {
    const res = await request.get('/monitor/v1/metrics', { params: { type: 'EXCHANGE' } })
    const rows = listOf((res.data || {}).points || [])
    exchangeTrend.labels = rows.map((r) => String(pick(r, ['time', 'date', 'label'], '')))
    exchangeTrend.values = rows.map((r) => numOf(pick(r, ['value', 'rowCount'], 0)))
    successTrend.labels = exchangeTrend.labels
    successTrend.values = rows.map((r) => {
      const sample = numOf(pick(r, ['sampleCount', 'sample', 'batchCount'], 0))
      const success = numOf(pick(r, ['successCount', 'success'], 0))
      const rate = pick(r, ['successRate'], null)
      if (rate !== null) return Number(rate)
      return sample ? Number(((success / sample) * 100).toFixed(2)) : 0
    })
  } catch (e) {
    exchangeTrend.labels = []
    exchangeTrend.values = []
    successTrend.labels = []
    successTrend.values = []
  }
}

async function loadReports() {
  try {
    const res = await request.get('/monitor/v1/reports', { params: { period: period.value } })
    const d = res.data || {}
    reportExchange.value = d.exchange || {}
    reportNode.value = d.node || {}
    deptRank.value = listOf(d.deptRanking || [])
    taskHealth.value = listOf(d.taskHealth || [])
  } catch (e) {
    reportExchange.value = {}
    reportNode.value = {}
    deptRank.value = []
    taskHealth.value = []
  }
}

async function loadAlarms() {
  try {
    const res = await request.get('/monitor/v1/alarms', { params: { pageNum: alarmPage.value, pageSize: 10 } })
    alarms.value = listOf(res.data)
    alarmTotal.value = res.page?.total ?? alarms.value.length
    alarmCount.value = alarms.value.filter((a) => !isAcked(a)).length
  } catch (e) {
    alarms.value = []
    alarmTotal.value = 0
  }
}

async function loadRules() {
  try {
    const res = await request.get('/monitor/v1/alarm-rules')
    rules.value = listOf(res.data)
  } catch (e) {
    rules.value = []
  }
}

function renderCharts() {
  if (exchangeTrend.labels.length && exchangeChartRef.value) {
    if (!exchangeChart) exchangeChart = echarts.init(exchangeChartRef.value)
    exchangeChart.setOption({
      color: ['#6f86ff'],
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis' },
      grid: { left: 52, right: 16, top: 20, bottom: 28 },
      xAxis: { type: 'category', data: exchangeTrend.labels, axisLabel: { color: '#93a4d8', fontSize: 11 }, axisLine: { lineStyle: { color: '#2a3550' } } },
      yAxis: { type: 'value', axisLabel: { color: '#93a4d8' }, splitLine: { lineStyle: { color: '#1c2740' } } },
      series: [{ name: '交换行数', type: 'line', smooth: true, showSymbol: false, areaStyle: { opacity: 0.25 }, data: exchangeTrend.values }]
    })
  }
  if (successTrend.labels.length && successChartRef.value) {
    if (!successChart) successChart = echarts.init(successChartRef.value)
    successChart.setOption({
      color: ['#4dd6a4'],
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis', valueFormatter: (v) => `${Number(v).toFixed(2)}%` },
      grid: { left: 50, right: 16, top: 20, bottom: 28 },
      xAxis: { type: 'category', data: successTrend.labels, axisLabel: { color: '#93a4d8', fontSize: 11 }, axisLine: { lineStyle: { color: '#2a3550' } } },
      yAxis: { type: 'value', max: 100, axisLabel: { color: '#93a4d8' }, splitLine: { lineStyle: { color: '#1c2740' } } },
      series: [{ name: '成功率', type: 'line', smooth: true, showSymbol: false, areaStyle: { opacity: 0.2 }, data: successTrend.values }]
    })
  }
  if (nodes.value.length && nodeChartRef.value) {
    if (!nodeChart) nodeChart = echarts.init(nodeChartRef.value)
    const names = nodes.value.map((n) => pick(n, ['nodeName', 'name'], `节点 #${n.id}`))
    const cpus = nodes.value.map((n) => numOf(pick(n, ['cpuUsage', 'cpu'], 0)))
    nodeChart.setOption({
      color: ['#f0b23e'],
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis' },
      grid: { left: 100, right: 24, top: 16, bottom: 24 },
      xAxis: { type: 'value', max: 100, axisLabel: { color: '#93a4d8' }, splitLine: { lineStyle: { color: '#1c2740' } } },
      yAxis: { type: 'category', data: names, axisLabel: { color: '#93a4d8', fontSize: 11 } },
      series: [{ name: 'CPU 使用率', type: 'bar', barMaxWidth: 16, data: cpus }]
    })
  }
  if (deptRank.value.length && deptChartRef.value) {
    if (!deptChart) deptChart = echarts.init(deptChartRef.value)
    const sorted = [...deptRank.value].sort((a, b) => numOf(a.rowCount) - numOf(b.rowCount)).slice(-8)
    deptChart.setOption({
      color: ['#6f86ff'],
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis' },
      grid: { left: 110, right: 24, top: 16, bottom: 24 },
      xAxis: { type: 'value', axisLabel: { color: '#93a4d8' }, splitLine: { lineStyle: { color: '#1c2740' } } },
      yAxis: { type: 'category', data: sorted.map((d) => pick(d, ['deptName'], '-')), axisLabel: { color: '#93a4d8', fontSize: 11 } },
      series: [{ name: '交换行数', type: 'bar', barMaxWidth: 16, data: sorted.map((d) => numOf(pick(d, ['rowCount'], 0))) }]
    })
  }
}

async function loadAll() {
  loading.value = true
  await Promise.allSettled([loadOverview(), loadNodes(), loadMetrics(), loadReports(), loadAlarms(), loadRules()])
  loading.value = false
  refreshTime.value = fmtDateTime(new Date())
  await nextTick()
  renderCharts()
  bindResize()
}

function bindResize() {
  disposeResize()
  const cleanups = [exchangeChart, successChart, nodeChart, deptChart].filter(Boolean).map((c) => autoResize(c))
  disposeResize = () => cleanups.forEach((fn) => fn())
}

function onTab(name) {
  if (name === 'alarms') loadAlarms()
  if (name === 'rules') loadRules()
  if (name === 'reports') {
    loadReports().then(() => nextTick().then(renderCharts))
  }
}

async function ack(row) {
  const ok = await confirmBox('确认该告警？确认后将不再计入活跃告警。', '确认告警')
  if (!ok) return
  try {
    await request.post(`/monitor/v1/alarms/${row.id}/ack`, { remark: '运维确认' })
    ElMessage.success('告警已确认')
    loadAlarms()
    loadOverview()
  } catch (e) {
    // 拦截器已提示
  }
}

function openRule(row) {
  if (row) {
    Object.assign(ruleForm, {
      id: row.id,
      ruleName: pick(row, ['ruleName', 'name'], ''),
      metricType: pick(row, ['metricType', 'metric'], 'TASK_FAIL'),
      operator: pick(row, ['operator'], '>'),
      threshold: numOf(pick(row, ['threshold'], 0)),
      durationSeconds: numOf(pick(row, ['durationSeconds'], 300)),
      alarmLevel: pick(row, ['alarmLevel', 'level'], 'P1'),
      channels: pick(row, ['channels'], 'INBOX'),
      targetCode: pick(row, ['targetCode'], ''),
      enabled: row.enabled !== false,
      description: pick(row, ['description'], '')
    })
  } else {
    Object.assign(ruleForm, emptyRule())
  }
  ruleVisible.value = true
}

async function saveRule() {
  try {
    await ruleRef.value.validate()
  } catch (e) {
    return
  }
  savingRule.value = true
  try {
    const payload = {
      ruleName: ruleForm.ruleName,
      metricType: ruleForm.metricType,
      operator: ruleForm.operator,
      threshold: ruleForm.threshold,
      durationSeconds: ruleForm.durationSeconds,
      alarmLevel: ruleForm.alarmLevel,
      channels: ruleForm.channels,
      targetCode: ruleForm.targetCode,
      enabled: ruleForm.enabled,
      description: ruleForm.description
    }
    if (ruleForm.id) await request.put(`/monitor/v1/alarm-rules/${ruleForm.id}`, payload)
    else await request.post('/monitor/v1/alarm-rules', payload)
    ElMessage.success('告警规则已保存')
    ruleVisible.value = false
    loadRules()
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingRule.value = false
  }
}

async function toggleRule(row, enabled) {
  try {
    await request.put(`/monitor/v1/alarm-rules/${row.id}`, {
      ruleName: pick(row, ['ruleName', 'name'], ''),
      metricType: pick(row, ['metricType'], ''),
      operator: pick(row, ['operator'], '>'),
      threshold: numOf(pick(row, ['threshold'], 0)),
      durationSeconds: numOf(pick(row, ['durationSeconds'], 300)),
      alarmLevel: pick(row, ['alarmLevel', 'level'], 'P2'),
      channels: pick(row, ['channels'], ''),
      enabled
    })
    ElMessage.success(enabled ? '规则已启用' : '规则已停用')
    loadRules()
  } catch (e) {
    // 拦截器已提示
  }
}

async function removeRule(row) {
  const ok = await confirmBox('确认删除该告警规则？', '删除规则', 'error')
  if (!ok) return
  try {
    await request.delete(`/monitor/v1/alarm-rules/${row.id}`)
    ElMessage.success('规则已删除')
    loadRules()
  } catch (e) {
    // 拦截器已提示
  }
}

onMounted(() => {
  loadAll()
  timer = setInterval(loadAll, 60000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  disposeResize()
  ;[exchangeChart, successChart, nodeChart, deptChart].forEach((c) => c && c.dispose())
})
</script>

<style scoped>
:deep(.el-tabs__item) {
  font-size: 13px;
}
</style>
