<template>
  <div v-loading="loading">
    <div v-if="!detail && !loading">
      <el-result icon="warning" title="服务不存在或无权访问">
        <template #extra><el-button type="primary" @click="$router.push('/share-api')">返回服务列表</el-button></template>
      </el-result>
    </div>

    <template v-else-if="detail">
      <div class="page-card">
        <div class="detail-hero">
          <div>
            <h3 class="detail-title">{{ nameOf }}</h3>
            <div class="flex-gap">
              <el-tag :type="tagType(detail.status)">{{ statusText(detail.status) }}</el-tag>
              <el-tag effect="plain" type="info" style="font-family: Consolas, monospace">
                {{ method }} /api/open/v1/{{ code }}
              </el-tag>
              <span class="muted">QPS：{{ pick(detail, ['qpsLimit', 'qps'], '-') }}</span>
            </div>
          </div>
          <div class="toolbar">
            <el-button :icon="Back" @click="$router.push('/share-api')">返回</el-button>
            <el-button v-if="isOnline" type="warning" plain @click="toggleOnline(false)">下线</el-button>
            <el-button v-else type="success" @click="toggleOnline(true)">上线</el-button>
            <el-button type="primary" :icon="CopyDocument" @click="copyUrl">复制调用地址</el-button>
          </div>
        </div>

        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="服务编码">{{ code }}</el-descriptions-item>
          <el-descriptions-item label="绑定目录">{{ pick(detail, ['catalogName', 'resourceName'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="挂接数据表">{{ pick(detail, ['sourceTable', 'tableName'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="凭据数量">{{ pick(detail, ['credentialCount'], 0) }}</el-descriptions-item>
          <el-descriptions-item label="QPS 限额">{{ pick(detail, ['qpsLimit'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="上线时间">{{ fmtDateTime(pick(detail, ['onlineTime', 'updateTime'])) }}</el-descriptions-item>
          <el-descriptions-item label="返回字段" :span="3">
            <div class="tag-list">
              <el-tag v-for="f in returnFields" :key="f" size="small" effect="plain">{{ f }}</el-tag>
              <span v-if="!returnFields.length" class="muted">全部字段</span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="脱敏字段" :span="3">
            <div class="tag-list">
              <el-tag v-for="f in maskedFields" :key="f" size="small" type="warning" effect="plain">{{ f }}</el-tag>
              <span v-if="!maskedFields.length" class="muted">无</span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="服务说明" :span="3">{{ pick(detail, ['remark', 'description'], '暂无说明') }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="page-card">
        <el-tabs v-model="tab">
          <el-tab-pane label="API 定义" name="def">
            <h4 class="section-title">请求参数</h4>
            <el-table :data="params" size="small" border stripe empty-text="该服务无请求参数">
              <el-table-column label="参数名" min-width="150">
                <template #default="{ row }">{{ pick(row, ['paramName', 'name'], '-') }}</template>
              </el-table-column>
              <el-table-column label="中文名" min-width="140">
                <template #default="{ row }">{{ pick(row, ['paramDesc', 'paramLabel', 'description'], '-') }}</template>
              </el-table-column>
              <el-table-column label="类型" width="110">
                <template #default="{ row }">{{ pick(row, ['paramType', 'type'], '-') }}</template>
              </el-table-column>
              <el-table-column label="必填" width="80" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.required" size="small" type="warning">必填</el-tag>
                  <span v-else class="muted">可选</span>
                </template>
              </el-table-column>
              <el-table-column label="示例值" width="140">
                <template #default="{ row }">{{ pick(row, ['exampleValue', 'defaultValue'], '-') }}</template>
              </el-table-column>
            </el-table>
            <div class="mt-12">
              <el-alert type="info" :closable="false" show-icon>
                调用方式：GET {{ '/api/open/v1/' + code }}?{{ paramQuery }}，请求头需携带 AppKey 鉴权（见凭据管理），返回统一结构
                {{ '{' }}code,message,traceId,data,page{{ '}' }}，敏感字段按脱敏规则返回。
              </el-alert>
            </div>
          </el-tab-pane>

          <el-tab-pane label="在线调试" name="debug">
            <el-form label-width="130px" style="max-width: 720px">
              <el-form-item v-for="p in params" :key="p.paramName || p.name" :label="paramLabel(p)">
                <el-input v-model="debugParams[paramKey(p)]" :placeholder="paramPlaceholder(p)" clearable />
              </el-form-item>
              <el-form-item v-if="!params.length" label="参数（JSON）">
                <el-input v-model="debugRaw" type="textarea" :rows="3" placeholder='{"pageNum":1,"pageSize":10}' />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :icon="VideoPlay" :loading="testing" @click="doTest">发送请求</el-button>
                <el-button @click="debugResult = ''">清空结果</el-button>
              </el-form-item>
            </el-form>
            <h4 class="section-title">响应结果</h4>
            <pre class="code-block">{{ debugResult || '点击「发送请求」查看返回数据' }}</pre>
          </el-tab-pane>

          <el-tab-pane label="接口文档" name="doc">
            <div class="flex-between mb-12">
              <span class="muted">根据服务定义自动生成的 OpenAPI 文档片段</span>
              <el-button size="small" :icon="Refresh" :loading="docLoading" @click="loadDoc">重新生成</el-button>
            </div>
            <pre v-if="docText" class="code-block">{{ docText }}</pre>
            <el-empty v-else description="文档暂不可用（接口未就绪或服务未配置）" :image-size="90" />
          </el-tab-pane>

          <el-tab-pane label="调用统计" name="stats">
            <div class="stat-grid mb-12">
              <div class="stat-card">
                <div class="stat-label">累计调用</div>
                <div class="stat-value">{{ fmtNumber(stats.total) }}</div>
              </div>
              <div class="stat-card">
                <div class="stat-label">成功调用</div>
                <div class="stat-value">{{ fmtNumber(stats.success) }}</div>
              </div>
              <div class="stat-card">
                <div class="stat-label">失败调用</div>
                <div class="stat-value">{{ fmtNumber(stats.failed) }}</div>
              </div>
              <div class="stat-card">
                <div class="stat-label">平均耗时</div>
                <div class="stat-value">{{ stats.avgCost }}</div>
              </div>
            </div>
            <div class="chart-grid">
              <div>
                <h4 class="section-title">调用量趋势</h4>
                <div v-if="trend.labels.length" ref="trendChartRef" class="chart-box" style="height: 280px" />
                <el-empty v-else description="暂无调用统计数据" :image-size="80" />
              </div>
              <div>
                <h4 class="section-title">成功率趋势</h4>
                <div v-if="rateTrend.labels.length" ref="rateChartRef" class="chart-box" style="height: 280px" />
                <el-empty v-else description="暂无成功率数据" :image-size="80" />
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back, CopyDocument, VideoPlay, Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import request from '@/api/request'
import { autoResize, fmtDateTime, fmtNumber, listOf, numOf, pick, tagType, toSeries } from '@/api/helpers'

const route = useRoute()
const id = route.params.id
const loading = ref(false)
const detail = ref(null)
const tab = ref('def')
const params = ref([])
const debugParams = reactive({})
const debugRaw = ref('{"pageNum":1,"pageSize":10}')
const debugResult = ref('')
const testing = ref(false)
const docText = ref('')
const docLoading = ref(false)
const stats = reactive({ total: '-', success: '-', failed: '-', avgCost: '-' })
const trend = reactive({ labels: [], values: [] })
const rateTrend = reactive({ labels: [], values: [] })
const trendChartRef = ref()
const rateChartRef = ref()
let trendChart = null
let rateChart = null
let disposeResize = () => {}

const STATUS_TEXT = { ONLINE: '已上线', OFFLINE: '已下线', DRAFT: '草稿', DISABLED: '已禁用' }

const nameOf = computed(() => pick(detail.value, ['apiName', 'name'], `服务 #${id}`))
const code = computed(() => pick(detail.value, ['apiCode', 'code'], '-'))
const method = computed(() => pick(detail.value, ['httpMethod', 'method'], 'GET').toUpperCase())
const isOnline = computed(() => String(detail.value?.status || '').toUpperCase() === 'ONLINE')
const returnFields = computed(() => {
  const f = pick(detail.value, ['returnFieldList', 'returnFields', 'fields'], [])
  if (Array.isArray(f)) return f.map((x) => (typeof x === 'string' ? x : pick(x, ['fieldName', 'name'], ''))).filter(Boolean)
  return String(f || '').split(',').map((s) => s.trim()).filter(Boolean)
})
const maskedFields = computed(() => {
  const f = pick(detail.value, ['maskedFieldList', 'maskFields'], [])
  if (Array.isArray(f)) return f.map((x) => String(x)).filter(Boolean)
  return String(f || '').split(',').map((s) => s.trim()).filter(Boolean)
})
const paramQuery = computed(() => {
  const keys = params.value.map((p) => paramKey(p)).filter(Boolean)
  if (!keys.length) return 'pageNum=1&pageSize=10'
  return keys.map((k, i) => `${k}=${i === 0 ? '值' : '值'}`).join('&')
})

function statusText(v) {
  return STATUS_TEXT[String(v || '').toUpperCase()] || v || '-'
}

function paramKey(p) {
  return pick(p, ['paramName', 'name', 'code'], '')
}

function paramLabel(p) {
  const label = pick(p, ['paramDesc', 'paramLabel', 'description'], '')
  return label ? `${paramKey(p)}（${label}）` : paramKey(p)
}

function paramPlaceholder(p) {
  const example = pick(p, ['exampleValue', 'defaultValue'], '')
  if (example) return `示例：${example}`
  return p.required ? '必填' : '可选'
}

async function loadDetail() {
  loading.value = true
  try {
    const res = await request.get(`/share/v1/apis/${id}`)
    const d = res.data || {}
    detail.value = d
    params.value = listOf(d.params || d.paramList || d.apiParams || [])
    params.value.forEach((p) => {
      const k = paramKey(p)
      if (k) debugParams[k] = pick(p, ['exampleValue', 'defaultValue', 'defaultVal'], '')
    })
  } catch (e) {
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function toggleOnline(online) {
  try {
    await request.post(`/share/v1/apis/${id}/${online ? 'online' : 'offline'}`)
    ElMessage.success(online ? '服务已上线' : '服务已下线')
    loadDetail()
  } catch (e) {
    // 拦截器已提示
  }
}

function copyUrl() {
  const url = `${window.location.origin}/api/open/v1/${code.value}`
  if (navigator.clipboard) {
    navigator.clipboard.writeText(url).then(
      () => ElMessage.success('调用地址已复制'),
      () => ElMessage.info(url)
    )
  } else {
    ElMessage.info(url)
  }
}

async function doTest() {
  testing.value = true
  debugResult.value = ''
  try {
    let payload
    if (params.value.length) {
      payload = {}
      Object.keys(debugParams).forEach((k) => {
        if (debugParams[k] !== '' && debugParams[k] !== null && debugParams[k] !== undefined) payload[k] = debugParams[k]
      })
    } else {
      try {
        payload = debugRaw.value ? JSON.parse(debugRaw.value) : {}
      } catch (e) {
        ElMessage.warning('参数 JSON 格式不正确')
        testing.value = false
        return
      }
    }
    const res = await request.post(`/share/v1/apis/${id}/test`, { params: payload, pageNum: 1, pageSize: 10 })
    debugResult.value = JSON.stringify(res, null, 2)
  } catch (e) {
    debugResult.value = JSON.stringify(
      { code: e?.response?.status ?? -1, message: e?.message || '调用失败' },
      null,
      2
    )
  } finally {
    testing.value = false
  }
}

async function loadDoc() {
  docLoading.value = true
  try {
    const res = await request.get(`/share/v1/apis/${id}/doc`)
    const d = res.data
    docText.value = typeof d === 'string' ? d : JSON.stringify(d, null, 2)
  } catch (e) {
    docText.value = ''
  } finally {
    docLoading.value = false
  }
}

async function loadStats() {
  try {
    const res = await request.get('/share/v1/stats', { params: { apiId: id } })
    const d = res.data || {}
    stats.total = pick(d, ['totalCalls', 'total', 'callCount'], '-')
    stats.success = pick(d, ['successCalls', 'success'], '-')
    stats.failed = pick(d, ['failCalls', 'failed'], '-')
    const avg = pick(d, ['avgCostMs', 'avgCost'], null)
    stats.avgCost = avg === null ? '-' : `${avg} ms`

    const rows = listOf(d.byDay || d.trend || [])
    if (rows.length) {
      trend.labels = rows.map((r) => String(pick(r, ['date', 'time', 'label'], '')))
      trend.values = rows.map((r) => numOf(pick(r, ['calls', 'count', 'value'], 0)))
      rateTrend.labels = trend.labels
      rateTrend.values = rows.map((r) => {
        const total = numOf(pick(r, ['calls', 'count', 'value'], 0))
        const ok = numOf(pick(r, ['successCalls', 'success'], total))
        return total ? Number(((ok / total) * 100).toFixed(2)) : 0
      })
    } else {
      const s = toSeries(d.trend || d.points || d)
      trend.labels = s.labels
      trend.values = s.values
    }
  } catch (e) {
    // 统计接口未就绪时保持空态
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
      series: [{ name: '调用量', type: 'bar', barMaxWidth: 26, data: trend.values }]
    })
  }
  if (rateTrend.labels.length && rateChartRef.value) {
    if (!rateChart) rateChart = echarts.init(rateChartRef.value)
    rateChart.setOption({
      color: ['#67c23a'],
      tooltip: { trigger: 'axis', valueFormatter: (v) => `${Number(v).toFixed(2)}%` },
      grid: { left: 48, right: 16, top: 20, bottom: 30 },
      xAxis: { type: 'category', data: rateTrend.labels, axisLabel: { fontSize: 11 } },
      yAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: '#eef1f7' } } },
      series: [{ name: '成功率', type: 'line', smooth: true, showSymbol: false, areaStyle: { opacity: 0.12 }, data: rateTrend.values }]
    })
  }
}

onMounted(async () => {
  await loadDetail()
  await Promise.allSettled([loadDoc(), loadStats()])
  await nextTick()
  renderCharts()
  const cleanups = [trendChart, rateChart].filter(Boolean).map((c) => autoResize(c))
  disposeResize = () => cleanups.forEach((fn) => fn())
})

onBeforeUnmount(() => {
  disposeResize()
  if (trendChart) {
    trendChart.dispose()
    trendChart = null
  }
  if (rateChart) {
    rateChart.dispose()
    rateChart = null
  }
})
</script>
