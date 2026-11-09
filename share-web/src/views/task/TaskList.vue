<template>
  <div v-loading="loading">
    <div class="page-card">
      <div class="page-header">
        <div>
          <h3 class="page-title">交换任务</h3>
          <div class="page-subtitle">任务定义、调度启停、手工运行与最近实例状态</div>
        </div>
        <div class="toolbar">
          <el-button type="primary" :icon="Plus" @click="$router.push('/task/create')">新建任务</el-button>
          <el-button :disabled="!selection.length" @click="batchEnable(true)">批量启用</el-button>
          <el-button :disabled="!selection.length" @click="batchEnable(false)">批量停用</el-button>
          <el-button :icon="Refresh" @click="load">刷新</el-button>
        </div>
      </div>

      <el-form inline class="filter-bar" @submit.prevent>
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" placeholder="任务名称 / 编码" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px">
            <el-option label="就绪" value="READY" />
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
            <el-option label="草稿" value="DRAFT" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="search">查询</el-button>
          <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" stripe border row-key="id" empty-text="暂无交换任务" @selection-change="(v) => (selection = v)">
        <el-table-column type="selection" width="46" />
        <el-table-column label="任务名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-link" @click="$router.push(`/task/${row.id}`)">{{ nameOf(row) }}</span>
            <div class="muted">{{ pick(row, ['code', 'taskCode'], '-') }}</div>
          </template>
        </el-table-column>
        <el-table-column label="源 → 目标" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ sourceOf(row) }} → {{ targetOf(row) }}</template>
        </el-table-column>
        <el-table-column label="调度策略" width="150">
          <template #default="{ row }">
            <span v-if="cronOf(row)" class="nowrap">{{ cronOf(row) }}</span>
            <span v-else class="muted">手工触发</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="isEnabled(row)"
              :loading="togglingId === row.id"
              inline-prompt
              active-text="启用"
              inactive-text="停用"
              @change="(v) => toggle(row, v)"
            />
            <div class="muted">{{ taskStatusText(row.status) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="最近运行" width="200">
          <template #default="{ row }">
            <div class="flex-gap">
              <el-tag v-if="lastRunStatus(row)" size="small" :type="tagType(lastRunStatus(row))">
                {{ runStatusText(lastRunStatus(row)) }}
              </el-tag>
              <span class="muted">{{ fmtDateTime(pick(row, ['lastInstanceTime', 'lastRunTime', 'updateTime'])) }}</span>
            </div>
            <div class="muted">耗时：{{ pick(row, ['lastCostMs'], '-') }} ms</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/task/${row.id}`)">详情</el-button>
            <el-button link type="success" :loading="runningId === row.id" @click="run(row)">运行</el-button>
            <el-button link type="primary" @click="$router.push(`/task/${row.id}?tab=instances`)">实例</el-button>
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
          @current-change="load"
          @size-change="search"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search, RefreshLeft } from '@element-plus/icons-vue'
import request from '@/api/request'
import { clean, confirmBox, fmtDateTime, listOf, pageOf, pick, tagType } from '@/api/helpers'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const query = reactive({ keyword: '', status: '' })
const selection = ref([])
const togglingId = ref(null)
const runningId = ref(null)

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

const TASK_STATUS_TEXT = {
  READY: '就绪',
  DRAFT: '草稿',
  ENABLED: '启用',
  DISABLED: '停用',
  PAUSED: '已暂停',
  RUNNING: '运行中'
}

function taskStatusText(v) {
  return TASK_STATUS_TEXT[String(v || '').toUpperCase()] || v || '-'
}

function nameOf(row) {
  return pick(row, ['name', 'taskName', 'title'], `任务 #${row?.id ?? ''}`)
}

function isEnabled(row) {
  if (typeof row.enabled === 'boolean') return row.enabled
  if (typeof row.status === 'boolean') return row.status
  return ['ENABLED', 'RUNNING', 'ACTIVE', 'ON', '1', 'TRUE'].includes(String(row.status ?? '').toUpperCase())
}

function runStatusText(v) {
  return RUN_STATUS_TEXT[String(v || '').toUpperCase()] || v || '-'
}

function lastRunStatus(row) {
  return pick(row, ['lastRunStatus', 'lastStatus', 'lastInstanceStatus'], '')
}

function cronOf(row) {
  return pick(row, ['scheduleCron', 'cron', 'scheduleCronText'], '') || ''
}

function sourceOf(row) {
  const src = pick(row, ['sourceName', 'sourceTable', 'sourceFilePath'], '')
  if (src) return src
  const d = definition(row)
  return pick(d, ['sourceName', 'sourceTable'], '') || d.source?.name || d.source?.tableName || '未配置'
}

function targetOf(row) {
  const tgt = pick(row, ['targetTable', 'targetFilePath'], '')
  if (tgt) return tgt
  const d = definition(row)
  return pick(d, ['targetTable'], '') || d.target?.tableName || d.target?.filePath || '未配置'
}

function definition(row) {
  const def = row.definition || (row.definitionJson ? (() => {
    try {
      return JSON.parse(row.definitionJson)
    } catch (e) {
      return {}
    }
  })() : null)
  if (!def) return {}
  if (typeof def === 'string') {
    try {
      return JSON.parse(def) || {}
    } catch (e) {
      return {}
    }
  }
  return def
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/task/v1/tasks', {
      params: clean({ ...query, pageNum: pageNum.value, pageSize: pageSize.value })
    })
    rows.value = listOf(res.data)
    total.value = pageOf(res).total
  } catch (e) {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  pageNum.value = 1
  load()
}

function reset() {
  query.keyword = ''
  query.status = ''
  search()
}

async function toggle(row, enabled) {
  togglingId.value = row.id
  try {
    await request.post(`/task/v1/tasks/${row.id}/${enabled ? 'enable' : 'disable'}`)
    ElMessage.success(enabled ? '任务已启用' : '任务已停用')
    load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    togglingId.value = null
  }
}

async function run(row) {
  const ok = await confirmBox(`确认立即运行任务「${nameOf(row)}」？`, '手工运行')
  if (!ok) return
  runningId.value = row.id
  try {
    const res = await request.post(`/task/v1/tasks/${row.id}/run`)
    const d = res.data || {}
    ElMessage.success(`任务已触发${d.batchId ? `，批次号：${d.batchId}` : ''}`)
    load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    runningId.value = null
  }
}

async function batchEnable(enabled) {
  if (!selection.value.length) return
  const ok = await confirmBox(`确认批量${enabled ? '启用' : '停用'} ${selection.value.length} 个任务？`, '批量操作')
  if (!ok) return
  try {
    await request.post('/task/v1/tasks/batch-enable', {
      ids: selection.value.map((r) => r.id),
      enabled
    })
    ElMessage.success(`已批量${enabled ? '启用' : '停用'} ${selection.value.length} 个任务`)
    selection.value = []
    load()
  } catch (e) {
    // 拦截器已提示
  }
}

onMounted(load)
</script>
