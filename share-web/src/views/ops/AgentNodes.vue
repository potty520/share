<template>
  <div class="page-card" v-loading="loading">
    <div class="page-header">
      <div>
        <h3 class="page-title">前置节点</h3>
        <div class="page-subtitle">前置交换节点在线状态、资源负载、心跳、命令与离线摆渡导入</div>
      </div>
      <div class="toolbar">
        <el-button :icon="Promotion" @click="openDispatch">下发命令</el-button>
        <el-button type="primary" :icon="Upload" @click="importVisible = true">离线摆渡导入</el-button>
        <el-button :icon="Refresh" @click="loadNodes">刷新</el-button>
      </div>
    </div>

    <div class="stat-grid stat-grid--4 mb-12">
      <div class="stat-card">
        <div class="stat-label">节点总数</div>
        <div class="stat-value">{{ nodes.length }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">在线节点</div>
        <div class="stat-value" style="color: #67c23a">{{ onlineCount }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">离线节点</div>
        <div class="stat-value" style="color: #f56c6c">{{ nodes.length - onlineCount }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">平均 CPU</div>
        <div class="stat-value">{{ avgCpu }}%</div>
      </div>
    </div>

    <el-table :data="nodes" size="small" stripe border empty-text="暂无前置节点">
      <el-table-column label="节点名称" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <div style="font-weight: 600">{{ nodeName(row) }}</div>
          <div class="muted">{{ pick(row, ['nodeCode', 'code', 'nodeId'], '-') }}</div>
        </template>
      </el-table-column>
      <el-table-column label="IP 地址" width="150">
        <template #default="{ row }">{{ pick(row, ['ip', 'host'], '-') }}{{ row.port ? ':' + row.port : '' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="isOnline(row) ? 'success' : 'danger'">{{ isOnline(row) ? '在线' : '离线' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="CPU" width="150">
        <template #default="{ row }">
          <el-progress :percentage="percentOf(row, ['cpuUsage', 'cpu'])" :stroke-width="8" :color="barColor" />
        </template>
      </el-table-column>
      <el-table-column label="内存" width="150">
        <template #default="{ row }">
          <el-progress :percentage="percentOf(row, ['memUsage', 'memoryUsage', 'memory'])" :stroke-width="8" :color="barColor" />
        </template>
      </el-table-column>
      <el-table-column label="磁盘" width="150">
        <template #default="{ row }">
          <el-progress :percentage="percentOf(row, ['diskUsage', 'disk'])" :stroke-width="8" :color="barColor" />
        </template>
      </el-table-column>
      <el-table-column label="版本" width="110">
        <template #default="{ row }">{{ pick(row, ['version', 'agentVersion'], '-') }}</template>
      </el-table-column>
      <el-table-column label="待执行命令" width="110" align="center">
        <template #default="{ row }">{{ pick(row, ['pendingCommands'], 0) }}</template>
      </el-table-column>
      <el-table-column label="最后心跳" width="160">
        <template #default="{ row }">{{ fmtDateTime(pick(row, ['lastHeartbeatTime', 'lastHeartbeat'])) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="primary" @click="openCommands(row)">命令记录</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 节点详情 -->
    <el-drawer v-model="detailVisible" title="节点详情与心跳历史" size="720px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="节点名称">{{ nodeName(detailNode) }}</el-descriptions-item>
        <el-descriptions-item label="节点编码">{{ pick(detailNode, ['nodeCode', 'nodeId'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="IP 地址">{{ pick(detailNode, ['ip'], '-') }}{{ detailNode.port ? ':' + detailNode.port : '' }}</el-descriptions-item>
        <el-descriptions-item label="在线状态">{{ isOnline(detailNode) ? '在线' : '离线' }}</el-descriptions-item>
        <el-descriptions-item label="Agent 版本">{{ pick(detailNode, ['version'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="证书指纹">{{ pick(detailNode, ['certFingerprint', 'fingerprint'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="最后心跳">{{ fmtDateTime(pick(detailNode, ['lastHeartbeatTime'])) }}</el-descriptions-item>
        <el-descriptions-item label="离线时长">{{ offlineText(detailNode) }}</el-descriptions-item>
        <el-descriptions-item label="心跳记录数">{{ pick(detailNode, ['heartbeatHistoryCount', 'heartbeatCount'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="已执行任务">{{ pick(detailNode, ['tasksDone'], '-') }}</el-descriptions-item>
      </el-descriptions>
      <h4 class="section-title mt-12">心跳历史</h4>
      <el-table :data="heartbeats" size="small" border stripe empty-text="暂无心跳记录">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtDateTime(pick(row, ['heartbeatTime', 'createTime', 'lastHeartbeatTime'])) }}</template>
        </el-table-column>
        <el-table-column label="CPU %" width="85" align="right">
          <template #default="{ row }">{{ pick(row, ['cpuUsage', 'cpu'], '-') }}</template>
        </el-table-column>
        <el-table-column label="内存 %" width="90" align="right">
          <template #default="{ row }">{{ pick(row, ['memUsage', 'memoryUsage'], '-') }}</template>
        </el-table-column>
        <el-table-column label="磁盘 %" width="90" align="right">
          <template #default="{ row }">{{ pick(row, ['diskUsage', 'disk'], '-') }}</template>
        </el-table-column>
        <el-table-column label="任务进度" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['taskProgress', 'progress', 'status'], '-') }}</template>
        </el-table-column>
      </el-table>
      <h4 class="section-title mt-12">最近命令</h4>
      <el-table :data="recentCommands" size="small" border stripe empty-text="暂无命令">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtDateTime(pick(row, ['createTime', 'issuedAt'], '-')) }}</template>
        </el-table-column>
        <el-table-column label="命令" width="150">
          <template #default="{ row }">{{ pick(row, ['commandType', 'type'], '-') }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ pick(row, ['status'], '-') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['result', 'resultMessage', 'message'], '-') }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 命令记录 -->
    <el-drawer v-model="commandVisible" :title="`命令记录 - ${nodeName(commandNode)}`" size="760px">
      <el-table :data="commands" size="small" border stripe empty-text="暂无命令记录">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtDateTime(pick(row, ['createTime', 'issuedAt'], '-')) }}</template>
        </el-table-column>
        <el-table-column label="命令" width="150">
          <template #default="{ row }">{{ pick(row, ['commandType', 'type'], '-') }}</template>
        </el-table-column>
        <el-table-column label="标题" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['title', 'commandTitle'], '-') }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ pick(row, ['status'], '-') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['result', 'resultMessage', 'message'], '-') }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 下发命令 -->
    <el-dialog v-model="dispatchVisible" title="管理端下发命令" width="560px" append-to-body>
      <el-form label-width="96px">
        <el-form-item label="目标节点">
          <el-select v-model="dispatchForm.nodeId" filterable placeholder="选择节点" style="width: 100%">
            <el-option v-for="n in nodes" :key="pick(n, ['nodeCode', 'nodeId'])" :label="nodeName(n)" :value="pick(n, ['nodeCode', 'nodeId'])" />
          </el-select>
        </el-form-item>
        <el-form-item label="命令类型">
          <el-select v-model="dispatchForm.commandType" style="width: 100%">
            <el-option label="执行任务 TASK_RUN" value="TASK_RUN" />
            <el-option label="停止任务 TASK_STOP" value="TASK_STOP" />
            <el-option label="文件推送 FILE_PUSH" value="FILE_PUSH" />
            <el-option label="摆渡导入 PACKAGE_IMPORT" value="PACKAGE_IMPORT" />
            <el-option label="升级 UPGRADE" value="UPGRADE" />
          </el-select>
        </el-form-item>
        <el-form-item label="命令标题">
          <el-input v-model="dispatchForm.title" placeholder="如：立即执行企业信息同步" />
        </el-form-item>
        <el-form-item label="命令负载">
          <el-input v-model="dispatchForm.payload" type="textarea" :rows="3" placeholder='可选 JSON，如 {"taskId":1}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dispatchVisible = false">取消</el-button>
        <el-button type="primary" :loading="dispatching" @click="doDispatch">下发</el-button>
      </template>
    </el-dialog>

    <!-- 离线摆渡导入 -->
    <el-dialog v-model="importVisible" title="离线摆渡包导入" width="600px" append-to-body>
      <el-form ref="importRef" :model="importForm" :rules="importRules" label-width="96px">
        <el-form-item label="摆渡包编号" prop="packageId">
          <el-input v-model="importForm.packageId" placeholder="如：PKG-20261109-001" />
        </el-form-item>
        <el-form-item label="摆渡包名称">
          <el-input v-model="importForm.packageName" placeholder="如：企业基础信息摆渡包" />
        </el-form-item>
        <el-form-item label="来源节点">
          <el-select v-model="importForm.nodeId" clearable filterable placeholder="选择来源节点" style="width: 100%">
            <el-option v-for="n in nodes" :key="pick(n, ['nodeCode', 'nodeId'])" :label="nodeName(n)" :value="pick(n, ['nodeCode', 'nodeId'])" />
          </el-select>
        </el-form-item>
        <el-form-item label="包校验值" prop="checksum">
          <el-input v-model="importForm.checksum" placeholder="包 MD5 校验值" />
        </el-form-item>
        <el-form-item label="签名信息">
          <el-input v-model="importForm.signature" placeholder="SHA-256(packageName|checksum|manifestJson|secret)" />
        </el-form-item>
        <el-form-item label="清单 JSON">
          <el-input
            v-model="importForm.manifestJson"
            type="textarea"
            :rows="4"
            placeholder='如 [{"fileName":"enterprise.csv","size":1024,"md5":"...","rows":100}]'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Upload, Promotion } from '@element-plus/icons-vue'
import request from '@/api/request'
import { fmtDateTime, listOf, numOf, pick, tagType } from '@/api/helpers'

const loading = ref(false)
const nodes = ref([])
const detailVisible = ref(false)
const detailNode = ref({})
const heartbeats = ref([])
const recentCommands = ref([])
const commandVisible = ref(false)
const commandNode = ref({})
const commands = ref([])

const dispatchVisible = ref(false)
const dispatching = ref(false)
const dispatchForm = reactive({ nodeId: '', commandType: 'TASK_RUN', title: '', payload: '' })

const importVisible = ref(false)
const importing = ref(false)
const importRef = ref()
const importForm = reactive({ packageId: '', packageName: '', nodeId: '', checksum: '', signature: '', manifestJson: '' })
const importRules = {
  packageId: [{ required: true, message: '请输入摆渡包编号', trigger: 'blur' }],
  checksum: [{ required: true, message: '请输入包校验值', trigger: 'blur' }]
}

const onlineCount = computed(() => nodes.value.filter((n) => isOnline(n)).length)
const avgCpu = computed(() => {
  if (!nodes.value.length) return 0
  const sum = nodes.value.reduce((acc, n) => acc + percentOf(n, ['cpuUsage', 'cpu']), 0)
  return Math.round(sum / nodes.value.length)
})

function nodeName(row) {
  return pick(row, ['nodeName', 'name'], `节点 #${pick(row, ['nodeCode', 'id'], '')}`)
}

function isOnline(row) {
  const s = String(row?.status ?? '').toUpperCase()
  if (s) return ['ONLINE', 'ACTIVE', 'UP'].includes(s)
  return !!row?.online
}

function percentOf(row, keys) {
  const v = numOf(pick(row, keys, 0))
  return Math.max(0, Math.min(100, Math.round(v)))
}

function barColor(percentage) {
  if (percentage >= 85) return '#f56c6c'
  if (percentage >= 65) return '#e6a23c'
  return '#67c23a'
}

function offlineText(row) {
  const sec = pick(row, ['offlineSeconds'], null)
  if (sec === null) return '-'
  const n = numOf(sec)
  if (n >= 3600) return `${(n / 3600).toFixed(1)} 小时`
  if (n >= 60) return `${Math.round(n / 60)} 分钟`
  return `${n} 秒`
}

async function loadNodes() {
  loading.value = true
  try {
    const res = await request.get('/agent/v1/nodes', { params: { pageNum: 1, pageSize: 100 } })
    nodes.value = listOf(res.data)
  } catch (e) {
    nodes.value = []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  detailNode.value = row
  heartbeats.value = []
  recentCommands.value = []
  detailVisible.value = true
  try {
    const res = await request.get(`/agent/v1/nodes/${pick(row, ['nodeCode', 'nodeId', 'id'])}`)
    const d = res.data || {}
    detailNode.value = d
    heartbeats.value = listOf(d.heartbeats || [])
    recentCommands.value = listOf(d.recentCommands || [])
  } catch (e) {
    // 保留列表信息
  }
}

async function openCommands(row) {
  commandNode.value = row
  commands.value = []
  commandVisible.value = true
  try {
    const res = await request.get('/agent/v1/commands/history', {
      params: { nodeId: pick(row, ['nodeCode', 'nodeId', 'id']), limit: 50 }
    })
    commands.value = listOf(res.data)
  } catch (e) {
    commands.value = []
  }
}

function openDispatch() {
  dispatchForm.nodeId = pick(nodes.value[0] || {}, ['nodeCode', 'nodeId'])
  dispatchForm.commandType = 'TASK_RUN'
  dispatchForm.title = ''
  dispatchForm.payload = ''
  dispatchVisible.value = true
}

async function doDispatch() {
  if (!dispatchForm.nodeId) {
    ElMessage.warning('请选择目标节点')
    return
  }
  dispatching.value = true
  try {
    let payload = {}
    if (dispatchForm.payload) {
      try {
        payload = JSON.parse(dispatchForm.payload)
      } catch (e) {
        ElMessage.warning('命令负载不是合法 JSON')
        dispatching.value = false
        return
      }
    }
    await request.post('/agent/v1/commands', {
      nodeId: dispatchForm.nodeId,
      commandType: dispatchForm.commandType,
      title: dispatchForm.title || dispatchForm.commandType,
      payload,
      priority: 5,
      expireMinutes: 30
    })
    ElMessage.success('命令已下发，节点将在下次轮询时执行')
    dispatchVisible.value = false
  } catch (e) {
    // 拦截器已提示
  } finally {
    dispatching.value = false
  }
}

async function doImport() {
  try {
    await importRef.value.validate()
  } catch (e) {
    return
  }
  importing.value = true
  try {
    const res = await request.post('/agent/v1/package/import', {
      packageId: importForm.packageId,
      packageName: importForm.packageName,
      nodeId: importForm.nodeId || undefined,
      checksum: importForm.checksum,
      signature: importForm.signature,
      manifestJson: importForm.manifestJson
    })
    const d = res.data || {}
    ElMessage.success(`摆渡包导入成功${d.fileCount ? `，共 ${d.fileCount} 个文件` : ''}`)
    importVisible.value = false
    loadNodes()
  } catch (e) {
    // 拦截器已提示
  } finally {
    importing.value = false
  }
}

onMounted(loadNodes)
</script>
