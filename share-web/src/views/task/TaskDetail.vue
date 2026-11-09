<template>
  <div v-loading="loading">
    <div v-if="!detail && !loading">
      <el-result icon="warning" title="任务不存在或无权访问">
        <template #extra><el-button type="primary" @click="$router.push('/task')">返回任务列表</el-button></template>
      </el-result>
    </div>

    <template v-else-if="detail">
      <div class="page-card">
        <div class="detail-hero">
          <div>
            <h3 class="detail-title">{{ nameOf }}</h3>
            <div class="flex-gap">
              <el-tag :type="tagType(detail.status)">{{ statusText(detail.status) }}</el-tag>
              <el-tag :type="isEnabled ? 'success' : 'info'" effect="plain">{{ isEnabled ? '调度已启用' : '调度已停用' }}</el-tag>
              <el-tag v-if="cron" effect="plain" type="warning">cron: {{ cron }}</el-tag>
              <el-tag v-if="detail.running" type="warning" effect="dark">运行中</el-tag>
              <span class="muted">{{ pick(detail, ['taskCode', 'code'], '-') }}</span>
            </div>
          </div>
          <div class="toolbar">
            <el-button :icon="Back" @click="$router.push('/task')">返回</el-button>
            <el-button type="success" :loading="running" @click="runNow">立即运行</el-button>
            <el-button type="danger" plain @click="stopCurrent">终止当前实例</el-button>
            <el-button plain @click="toggleEnable">{{ isEnabled ? '停用任务' : '启用任务' }}</el-button>
            <el-button plain @click="scheduleVisible = true">调度设置</el-button>
          </div>
        </div>

        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="任务编码">{{ pick(detail, ['taskCode', 'code'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="任务类型">{{ taskTypeText(detail.taskType) }}</el-descriptions-item>
          <el-descriptions-item label="源端">{{ sourceOf }}</el-descriptions-item>
          <el-descriptions-item label="目标端">{{ targetOf }}</el-descriptions-item>
          <el-descriptions-item label="调度 cron">{{ cron || '手工触发' }}</el-descriptions-item>
          <el-descriptions-item label="最近运行">{{ fmtDateTime(pick(detail, ['lastRunTime', 'updateTime'])) }}</el-descriptions-item>
          <el-descriptions-item label="最近批次">{{ pick(detail, ['lastBatchId'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="所属部门">{{ pick(detail, ['deptName'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="创建人">{{ pick(detail, ['createBy', 'creatorName', 'operatorName'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="任务描述" :span="3">{{ pick(detail, ['remark', 'description'], '暂无描述') }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="page-card">
        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="字段映射与算子" name="mapping">
            <h4 class="section-title">字段映射</h4>
            <el-table :data="mapping" size="small" border stripe empty-text="暂无字段映射配置（原样透传）">
              <el-table-column type="index" label="#" width="50" />
              <el-table-column label="源字段" min-width="150">
                <template #default="{ row }">{{ pick(row, ['sourceField', 'source'], '-') }}</template>
              </el-table-column>
              <el-table-column label="目标字段" min-width="150">
                <template #default="{ row }">{{ pick(row, ['targetField', 'target'], '-') }}</template>
              </el-table-column>
              <el-table-column label="转换规则" width="150">
                <template #default="{ row }">{{ pick(row, ['transform'], '-') }}</template>
              </el-table-column>
              <el-table-column label="脱敏算法" width="130">
                <template #default="{ row }">{{ pick(row, ['maskType'], '-') }}</template>
              </el-table-column>
              <el-table-column label="脱敏参数" width="120">
                <template #default="{ row }">{{ pick(row, ['maskParam'], '-') }}</template>
              </el-table-column>
            </el-table>

            <h4 class="section-title mt-12">加工算子链</h4>
            <el-table :data="operatorList" size="small" border stripe empty-text="未配置算子">
              <el-table-column type="index" label="#" width="50" />
              <el-table-column label="算子" width="180">
                <template #default="{ row }">{{ pick(row, ['type', 'code', 'name'], '-') }}</template>
              </el-table-column>
              <el-table-column label="参数" min-width="240">
                <template #default="{ row }">
                  <span style="font-family: Consolas, monospace">{{ paramText(row) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="依赖任务" name="deps">
            <div class="flex-between mb-12">
              <span class="muted">上游任务成功后才调度本任务，保存时后端会做环检测</span>
              <el-button size="small" type="primary" :icon="Plus" @click="openAddDep">添加上游依赖</el-button>
            </div>
            <el-table :data="deps" size="small" border stripe empty-text="无上游依赖">
              <el-table-column label="上游任务" min-width="200">
                <template #default="{ row }">{{ pick(row, ['upTaskName', 'name'], `任务 #${pick(row, ['upTaskId', 'id'], '-')}`) }}</template>
              </el-table-column>
              <el-table-column label="上游编码" width="150">
                <template #default="{ row }">{{ pick(row, ['upTaskCode'], '-') }}</template>
              </el-table-column>
              <el-table-column label="依赖类型" width="120">
                <template #default="{ row }">{{ pick(row, ['depType'], 'SUCCESS') }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button link type="danger" @click="removeDep(row)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="实例列表" name="instances">
            <div class="flex-between mb-12">
              <div class="toolbar">
                <el-select v-model="instanceStatus" clearable placeholder="全部状态" size="small" style="width: 130px" @change="loadInstances">
                  <el-option label="成功" value="SUCCESS" />
                  <el-option label="失败" value="FAILED" />
                  <el-option label="运行中" value="RUNNING" />
                  <el-option label="等待中" value="WAITING" />
                  <el-option label="已终止" value="STOPPED" />
                </el-select>
                <el-button size="small" :icon="Refresh" @click="loadInstances">刷新</el-button>
              </div>
              <span class="muted">共 {{ instanceTotal }} 个实例</span>
            </div>
            <el-table :data="instances" size="small" border stripe empty-text="暂无运行实例">
              <el-table-column label="实例ID" width="90">
                <template #default="{ row }">{{ pick(row, ['id', 'instanceId'], '-') }}</template>
              </el-table-column>
              <el-table-column label="批次号" min-width="170" show-overflow-tooltip>
                <template #default="{ row }">{{ pick(row, ['batchId', 'batchNo'], '-') }}</template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="tagType(row.status)">{{ runStatusText(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="进度" width="90" align="right">
                <template #default="{ row }">{{ pick(row, ['progress'], 0) }}%</template>
              </el-table-column>
              <el-table-column label="行数" width="110" align="right">
                <template #default="{ row }">{{ fmtNumber(pick(row, ['totalRows', 'writeRows', 'rowCount'], '-')) }}</template>
              </el-table-column>
              <el-table-column label="耗时" width="100" align="right">
                <template #default="{ row }">{{ durationText(row) }}</template>
              </el-table-column>
              <el-table-column label="开始时间" width="160">
                <template #default="{ row }">{{ fmtDateTime(pick(row, ['startTime', 'beginTime', 'createTime'])) }}</template>
              </el-table-column>
              <el-table-column label="错误信息" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">
                  <span :class="{ 'sensitive-field': !!row.errorMsg }">{{ pick(row, ['errorMsg', 'message'], '-') }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="210" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openLog(row)">日志</el-button>
                  <el-button link type="primary" @click="rerun(row)">重跑</el-button>
                  <el-button link type="warning" @click="verify(row)">一致性核对</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="pager">
              <el-pagination
                v-model:current-page="instancePage"
                :page-size="instanceSize"
                background
                layout="total, prev, pager, next"
                :total="instanceTotal"
                @current-change="loadInstances"
              />
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>

    <!-- 日志 -->
    <el-drawer v-model="logVisible" title="实例运行日志" size="700px">
      <div class="flex-between mb-12">
        <span class="muted">实例 #{{ logInstanceId }}</span>
        <el-button size="small" :icon="Refresh" @click="loadLog">刷新</el-button>
      </div>
      <pre class="code-block">{{ logText || '暂无日志' }}</pre>
    </el-drawer>

    <!-- 一致性核对 -->
    <el-dialog v-model="verifyVisible" title="一致性核对结果" width="560px" append-to-body>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="核对结果">
          <el-tag :type="verifyResult.passed ? 'success' : 'danger'">{{ verifyResult.passed ? '一致' : '不一致' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="源端">{{ verifyResult.source }}</el-descriptions-item>
        <el-descriptions-item label="目标端">{{ verifyResult.target }}</el-descriptions-item>
        <el-descriptions-item label="说明">{{ verifyResult.message }}</el-descriptions-item>
      </el-descriptions>
      <pre class="code-block mt-12" style="max-height: 240px">{{ verifyResult.raw }}</pre>
      <template #footer><el-button type="primary" @click="verifyVisible = false">关闭</el-button></template>
    </el-dialog>

    <!-- 调度设置 -->
    <el-dialog v-model="scheduleVisible" title="调度设置" width="480px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="cron 表达式">
          <el-input v-model="scheduleForm.cron" placeholder="如：0 0 2 * * ?（Spring 6 段 cron）" />
        </el-form-item>
        <el-form-item label="启用调度">
          <el-switch v-model="scheduleForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scheduleVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingSchedule" @click="saveSchedule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 添加上游依赖 -->
    <el-dialog v-model="depVisible" title="添加上游依赖" width="480px" append-to-body>
      <el-select v-model="depUpId" filterable placeholder="选择上游任务" style="width: 100%">
        <el-option v-for="t in allTasks" :key="t.id" :label="pick(t, ['taskName', 'name'], `任务 #${t.id}`)" :value="t.id" />
      </el-select>
      <template #footer>
        <el-button @click="depVisible = false">取消</el-button>
        <el-button type="primary" @click="addDep">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back, Plus, Refresh } from '@element-plus/icons-vue'
import request from '@/api/request'
import { confirmBox, fmtDateTime, fmtNumber, listOf, numOf, pick, tagType } from '@/api/helpers'

const route = useRoute()
const id = route.params.id

const loading = ref(false)
const detail = ref(null)
const tab = ref('mapping')
const mapping = ref([])
const operatorList = ref([])
const deps = ref([])
const instances = ref([])
const instanceTotal = ref(0)
const instancePage = ref(1)
const instanceSize = 10
const instanceStatus = ref('')
const running = ref(false)
const allTasks = ref([])
const depVisible = ref(false)
const depUpId = ref(null)
const scheduleVisible = ref(false)
const savingSchedule = ref(false)
const scheduleForm = reactive({ cron: '', enabled: true })
const logVisible = ref(false)
const logText = ref('')
const logInstanceId = ref('')
const verifyVisible = ref(false)
const verifyResult = reactive({ passed: true, source: '-', target: '-', message: '-', raw: '' })

const RUN_STATUS_TEXT = {
  SUCCESS: '成功',
  FAILED: '失败',
  RUNNING: '运行中',
  WAITING: '等待中',
  STOPPED: '已终止',
  RETRY: '重试中',
  PENDING: '待执行',
  SKIPPED: '已跳过'
}
const STATUS_TEXT = { DRAFT: '草稿', READY: '就绪', ENABLED: '启用', DISABLED: '停用', RUNNING: '运行中', STOPPED: '已停止', PAUSED: '已暂停' }

const nameOf = computed(() => pick(detail.value, ['taskName', 'name'], `任务 #${id}`))
const cron = computed(() => pick(detail.value, ['scheduleCron', 'cron'], ''))
const isEnabled = computed(() => {
  const d = detail.value || {}
  if (typeof d.enabled === 'boolean') return d.enabled
  return ['ENABLED', 'ACTIVE', 'RUNNING'].includes(String(d.status ?? '').toUpperCase())
})
const sourceOf = computed(() => {
  const d = detail.value || {}
  const parts = [pick(d, ['sourceName'], ''), pick(d, ['sourceTable', 'sourceFilePath'], '')].filter(Boolean)
  return parts.length ? parts.join(' / ') : pick(d, ['sourceType'], '未配置')
})
const targetOf = computed(() => {
  const d = detail.value || {}
  const path = pick(d, ['targetTable', 'targetFilePath'], '')
  return path ? `${pick(d, ['targetType'], '')} ${path}` : pick(d, ['targetType'], '未配置')
})

function statusText(v) {
  return STATUS_TEXT[String(v || '').toUpperCase()] || v || '-'
}

function runStatusText(v) {
  return RUN_STATUS_TEXT[String(v || '').toUpperCase()] || v || '-'
}

function taskTypeText(v) {
  const s = String(v || '').toUpperCase()
  if (s === 'FULL') return '全量同步'
  if (s === 'INCREMENT') return '增量同步'
  return v || '-'
}

function durationText(row) {
  const ms = pick(row, ['costMs', 'durationMs', 'elapsedMs'], null)
  if (ms !== null && ms !== '') {
    const n = numOf(ms)
    if (n >= 60000) return `${(n / 60000).toFixed(1)} 分`
    if (n >= 1000) return `${(n / 1000).toFixed(1)} 秒`
    return `${n} 毫秒`
  }
  return '-'
}

function paramText(row) {
  const p = row.params ?? row.param ?? row.config
  if (p === undefined || p === null || p === '') return '-'
  return typeof p === 'string' ? p : JSON.stringify(p)
}

async function loadDetail() {
  loading.value = true
  try {
    const res = await request.get(`/task/v1/tasks/${id}`)
    const d = res.data || {}
    detail.value = d
    mapping.value = listOf(d.mapping || [])
    operatorList.value = listOf(d.operators || [])
    scheduleForm.cron = pick(d, ['scheduleCron', 'cron'], '')
    scheduleForm.enabled = isEnabled.value
  } catch (e) {
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function loadDeps() {
  try {
    const res = await request.get(`/task/v1/tasks/${id}/deps`)
    const d = res.data || {}
    deps.value = listOf(d.upstream || d.list || [])
  } catch (e) {
    deps.value = []
  }
}

async function loadInstances() {
  try {
    const res = await request.get(`/task/v1/tasks/${id}/instances`, {
      params: { pageNum: instancePage.value, pageSize: instanceSize, status: instanceStatus.value || undefined }
    })
    instances.value = listOf(res.data)
    instanceTotal.value = res.page?.total ?? instances.value.length
  } catch (e) {
    instances.value = []
    instanceTotal.value = 0
  }
}

async function loadTasks() {
  try {
    const res = await request.get('/task/v1/tasks', { params: { pageNum: 1, pageSize: 200 } })
    allTasks.value = listOf(res.data).filter((t) => String(t.id) !== String(id))
  } catch (e) {
    allTasks.value = []
  }
}

async function runNow() {
  const ok = await confirmBox(`确认立即运行任务「${nameOf.value}」？`, '手工运行')
  if (!ok) return
  running.value = true
  try {
    const res = await request.post(`/task/v1/tasks/${id}/run`)
    const d = res.data || {}
    ElMessage.success(`任务已触发${d.batchId ? `，批次号：${d.batchId}` : ''}`)
    loadInstances()
    loadDetail()
  } catch (e) {
    // 拦截器已提示
  } finally {
    running.value = false
  }
}

async function stopCurrent() {
  const ok = await confirmBox('确认终止当前运行实例？', '终止实例')
  if (!ok) return
  try {
    await request.post(`/task/v1/tasks/${id}/stop`)
    ElMessage.success('已发送终止指令')
    loadInstances()
  } catch (e) {
    // 拦截器已提示
  }
}

async function toggleEnable() {
  const target = !isEnabled.value
  try {
    await request.post(`/task/v1/tasks/${id}/${target ? 'enable' : 'disable'}`)
    ElMessage.success(target ? '任务已启用' : '任务已停用')
    loadDetail()
  } catch (e) {
    // 拦截器已提示
  }
}

async function saveSchedule() {
  savingSchedule.value = true
  try {
    await request.post(`/task/v1/tasks/${id}/schedule`, { cron: scheduleForm.cron, enabled: scheduleForm.enabled })
    ElMessage.success('调度设置已保存')
    scheduleVisible.value = false
    loadDetail()
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingSchedule.value = false
  }
}

async function openLog(row) {
  logInstanceId.value = pick(row, ['id', 'instanceId'], '')
  logText.value = ''
  logVisible.value = true
  await loadLog()
}

async function loadLog() {
  try {
    const res = await request.get(`/task/v1/instances/${logInstanceId.value}/logs`, { params: { pageNum: 1, pageSize: 200, tail: true } })
    const rows = listOf(res.data)
    logText.value = rows
      .map((x) => {
        if (typeof x === 'string') return x
        const time = fmtDateTime(pick(x, ['logTime', 'createTime'], ''))
        const level = pick(x, ['level'], 'INFO')
        const stage = pick(x, ['stage'], '')
        const msg = pick(x, ['message', 'content', 'log'], '')
        const rowsCount = pick(x, ['rowsCount'], '')
        return `[${time}] [${level}] ${stage ? stage + ' - ' : ''}${msg}${rowsCount !== '' ? `（${rowsCount} 行）` : ''}`
      })
      .join('\n')
  } catch (e) {
    logText.value = '日志加载失败或该实例暂无日志'
  }
}

async function rerun(row) {
  const ok = await confirmBox('确认重跑该实例？', '重跑实例')
  if (!ok) return
  try {
    const res = await request.post(`/task/v1/instances/${pick(row, ['id', 'instanceId'])}/rerun`)
    ElMessage.success(`已触发重跑${res.data?.batchId ? `，新批次号：${res.data.batchId}` : ''}`)
    loadInstances()
  } catch (e) {
    // 拦截器已提示
  }
}

async function verify(row) {
  try {
    const res = await request.post(`/task/v1/instances/${pick(row, ['id', 'instanceId'])}/verify`)
    const d = res.data || {}
    const passed = d.verified !== undefined ? !!d.verified : d.md5Match !== undefined ? !!(d.md5Match && d.sha256Match !== false && d.rowMatch !== false) : d.passed !== undefined ? !!d.passed : true
    verifyResult.passed = passed
    verifyResult.source = fmtNumber(pick(d, ['expectedRows', 'sourceRows', 'expectedRowCount'], '-')) + ' 行 / MD5 ' + pick(d, ['expectedMd5', 'sourceMd5'], '-')
    verifyResult.target = fmtNumber(pick(d, ['actualRows', 'targetRows', 'actualRowCount'], '-')) + ' 行 / MD5 ' + pick(d, ['actualMd5', 'targetMd5'], '-')
    verifyResult.message = pick(d, ['message', 'result', 'detail'], passed ? '数据一致' : '数据存在差异')
    verifyResult.raw = JSON.stringify(d, null, 2)
    verifyVisible.value = true
  } catch (e) {
    ElMessage.warning('一致性核对接口暂不可用')
  }
}

function openAddDep() {
  depUpId.value = null
  depVisible.value = true
  if (!allTasks.value.length) loadTasks()
}

async function addDep() {
  if (!depUpId.value) {
    ElMessage.warning('请选择上游任务')
    return
  }
  try {
    await request.post(`/task/v1/tasks/${id}/deps`, { upTaskId: depUpId.value })
    ElMessage.success('依赖已添加')
    depVisible.value = false
    loadDeps()
  } catch (e) {
    // 拦截器已提示（含环形依赖错误）
  }
}

async function removeDep(row) {
  const ok = await confirmBox('确认移除该上游依赖？', '移除依赖')
  if (!ok) return
  try {
    await request.delete(`/task/v1/tasks/${id}/deps/${pick(row, ['upTaskId', 'id'])}`)
    ElMessage.success('依赖已移除')
    loadDeps()
  } catch (e) {
    // 拦截器已提示
  }
}

function onTabChange(name) {
  if (name === 'instances') loadInstances()
  if (name === 'deps') {
    loadDeps()
    loadTasks()
  }
}

onMounted(() => {
  loadDetail()
  loadInstances()
  loadDeps()
  if (route.query.tab) tab.value = String(route.query.tab)
})
</script>
