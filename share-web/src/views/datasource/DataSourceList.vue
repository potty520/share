<template>
  <div v-loading="loading">
    <div class="page-card">
      <div class="page-header">
        <div>
          <h3 class="page-title">数据源管理</h3>
          <div class="page-subtitle">接入数据库、文件服务与接口类数据源，支持连通性测试与启停</div>
        </div>
        <div class="toolbar">
          <el-button type="primary" :icon="Plus" @click="openCreate">新增数据源</el-button>
          <el-button :icon="Refresh" @click="load">刷新</el-button>
        </div>
      </div>

      <el-form inline class="filter-bar" @submit.prevent>
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" placeholder="名称 / 编码 / 主机" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.type" clearable placeholder="全部类型" style="width: 150px">
            <el-option v-for="t in types" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="search">查询</el-button>
          <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" stripe border empty-text="暂无数据源，请先接入">
        <el-table-column label="名称" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <div style="font-weight: 600">{{ nameOf(row) }}</div>
            <div class="muted">{{ pick(row, ['sourceCode', 'code'], '-') }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ pick(row, ['typeName'], typeNameOf(row)) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ addrOf(row) }}</template>
        </el-table-column>
        <el-table-column label="库 / 路径" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['dbName', 'databaseName', 'filePath', 'apiUrl'], '-') }}</template>
        </el-table-column>
        <el-table-column label="部门" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['deptName', 'departmentName'], '-') }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-switch
              :model-value="isEnabled(row)"
              :loading="statusLoading === row.id"
              inline-prompt
              active-text="启用"
              inactive-text="停用"
              @change="(v) => toggleStatus(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="最近测试" width="200">
          <template #default="{ row }">
            <span class="muted">{{ fmtDateTime(pick(row, ['lastTestTime', 'testTime', 'updateTime'])) }}</span>
            <div class="muted" style="max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">
              {{ pick(row, ['lastTestResult'], '') }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="testingId === row.id" @click="test(row)">测试</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openHistory(row)">历史</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
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

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据源' : '新增数据源'" width="720px" append-to-body destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="数据源名称" prop="sourceName">
              <el-input v-model="form.sourceName" placeholder="如：财务部 MySQL" maxlength="60" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="数据源编码" prop="sourceCode">
              <el-input v-model="form.sourceCode" placeholder="如：DS-TAX-MYSQL" maxlength="60" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="类型" prop="sourceType">
              <el-select v-model="form.sourceType" style="width: 100%" @change="onTypeChange">
                <el-option v-for="t in types" :key="t.code" :label="t.name" :value="t.code" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="所属部门">
              <el-select v-model="form.deptId" clearable filterable placeholder="选择部门" style="width: 100%">
                <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-col>

          <template v-if="category === 'DB' || category === 'FILE'">
            <el-col :span="12">
              <el-form-item label="主机地址">
                <el-input v-model="form.host" placeholder="127.0.0.1 或域名" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="端口">
                <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" style="width: 100%" />
              </el-form-item>
            </el-col>
          </template>

          <el-col v-if="category === 'DB'" :span="12">
            <el-form-item label="数据库名">
              <el-input v-model="form.dbName" placeholder="库名 / SID" />
            </el-form-item>
          </el-col>
          <el-col v-if="category === 'FILE'" :span="12">
            <el-form-item label="目录 / 文件路径">
              <el-input v-model="form.filePath" placeholder="/data/share" />
            </el-form-item>
          </el-col>

          <el-col v-if="category === 'MQ'" :span="12">
            <el-form-item label="Broker 地址">
              <el-input v-model="form.host" placeholder="kafka1:9092" />
            </el-form-item>
          </el-col>
          <el-col v-if="category === 'MQ' || category === 'API'" :span="12">
            <el-form-item label="扩展配置">
              <el-input v-model="form.extraConfig" placeholder='如 {"topic":"share-topic"} 或 {"method":"GET"}' />
            </el-form-item>
          </el-col>

          <el-col v-if="category === 'API'" :span="24">
            <el-form-item label="接口地址">
              <el-input v-model="form.apiUrl" placeholder="https://api.example.com/v1/data" />
            </el-form-item>
          </el-col>
          <el-col v-if="category === 'API'" :span="12">
            <el-form-item label="请求方法">
              <el-select v-model="form.method" style="width: 100%">
                <el-option label="GET" value="GET" />
                <el-option label="POST" value="POST" />
              </el-select>
            </el-form-item>
          </el-col>

          <el-col :span="12">
            <el-form-item label="责任人">
              <el-input v-model="form.owner" placeholder="数据源责任人" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="密级">
              <el-select v-model="form.secretLevel" style="width: 100%">
                <el-option label="1 级 公开" :value="1" />
                <el-option label="2 级 内部" :value="2" />
                <el-option label="3 级 敏感" :value="3" />
                <el-option label="4 级 核心" :value="4" />
              </el-select>
            </el-form-item>
          </el-col>

          <template v-if="category !== 'API'">
            <el-col :span="12">
              <el-form-item label="用户名">
                <el-input v-model="form.username" placeholder="连接用户名" autocomplete="off" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="口令">
                <el-input v-model="form.password" type="password" show-password placeholder="编辑时留空表示不修改" autocomplete="new-password" />
              </el-form-item>
            </el-col>
          </template>

          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="300" show-word-limit />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 测试结果 -->
    <el-dialog v-model="testVisible" title="连通性测试结果" width="500px" append-to-body>
      <el-result
        :icon="testResult.ok ? 'success' : 'error'"
        :title="testResult.ok ? '连接成功' : '连接失败'"
        :sub-title="testResult.message"
      />
      <el-descriptions v-if="testResult.ok" :column="1" border size="small">
        <el-descriptions-item label="探测方式">{{ testResult.probe }}</el-descriptions-item>
        <el-descriptions-item label="探测目标">{{ testResult.target }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ testResult.latency }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="testVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- 变更历史 -->
    <el-drawer v-model="historyVisible" title="数据源变更历史" size="680px">
      <el-table :data="history" size="small" stripe border empty-text="暂无变更记录">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtDateTime(pick(row, ['operateTime', 'createTime'])) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="110">
          <template #default="{ row }">{{ pick(row, ['operator', 'createBy'], '-') }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">{{ pick(row, ['actionDesc', 'action'], '-') }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.result || 'SUCCESS')">{{ pick(row, ['result'], '-') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="变更内容" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['detail', 'content', 'remark'], '-') }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search, RefreshLeft } from '@element-plus/icons-vue'
import request from '@/api/request'
import { clean, confirmBox, fmtDateTime, listOf, pageOf, pick, tagType } from '@/api/helpers'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const query = reactive({ keyword: '', type: '', status: '' })
const types = ref([])
const depts = ref([])

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive(emptyForm())
const formRules = {
  sourceName: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  sourceCode: [{ required: true, message: '请输入数据源编码', trigger: 'blur' }],
  sourceType: [{ required: true, message: '请选择数据源类型', trigger: 'change' }]
}

const testingId = ref(null)
const statusLoading = ref(null)
const testVisible = ref(false)
const testResult = reactive({ ok: true, message: '', latency: '-', version: '-', probe: '-', target: '-' })

const historyVisible = ref(false)
const history = ref([])

const category = computed(() => {
  const t = types.value.find((x) => x.code === form.sourceType)
  if (t && t.category) return String(t.category).toUpperCase()
  const code = String(form.sourceType || '').toUpperCase()
  if (['MYSQL', 'ORACLE', 'POSTGRESQL', 'PG', 'SQLSERVER', 'DM', 'DAMENG', 'HIVE', 'CLICKHOUSE'].includes(code)) return 'DB'
  if (['FTP', 'SFTP'].includes(code)) return 'FILE'
  if (['OSS', 'S3', 'MINIO'].includes(code)) return 'FILE'
  if (['KAFKA', 'MQ', 'RABBITMQ'].includes(code)) return 'MQ'
  if (['HTTP', 'WEBSERVICE', 'API', 'REST'].includes(code)) return 'API'
  return 'DB'
})

function emptyForm() {
  return {
    id: null,
    sourceName: '',
    sourceCode: '',
    sourceType: 'MYSQL',
    deptId: null,
    host: '',
    port: 3306,
    dbName: '',
    username: '',
    password: '',
    filePath: '',
    apiUrl: '',
    method: 'GET',
    extraConfig: '',
    owner: '',
    secretLevel: 2,
    remark: ''
  }
}

function nameOf(row) {
  return pick(row, ['sourceName', 'name', 'title'], `数据源 #${row?.id ?? ''}`)
}

function typeNameOf(row) {
  const code = pick(row, ['sourceType', 'type'], '')
  const t = types.value.find((x) => x.code === code)
  return t?.name || code || '-'
}

function isEnabled(row) {
  if (typeof row.enabled === 'boolean') return row.enabled
  return String(row.status || '').toUpperCase() === 'ENABLED'
}

function addrOf(row) {
  const host = pick(row, ['host', 'ip', 'hostname', 'endpoint'], '')
  const port = pick(row, ['port'], '')
  if (host) return port ? `${host}:${port}` : String(host)
  return pick(row, ['apiUrl', 'address', 'url'], '-')
}

function normalizeTypes(data) {
  const list = listOf(data)
  if (!list.length && data && typeof data === 'object' && !Array.isArray(data)) {
    return Object.keys(data).map((k) => ({ code: k, name: data[k], category: '' }))
  }
  return list
    .map((t) => {
      if (typeof t === 'string') return { code: t, name: t, category: '' }
      return {
        code: pick(t, ['code', 'typeCode', 'type', 'value'], ''),
        name: pick(t, ['name', 'typeName', 'label', 'desc', 'code'], ''),
        category: pick(t, ['category'], ''),
        defaultPort: pick(t, ['defaultPort'], null)
      }
    })
    .filter((t) => t.code)
}

const DEFAULT_TYPES = [
  { code: 'MYSQL', name: 'MySQL', category: 'DB', defaultPort: 3306 },
  { code: 'ORACLE', name: 'Oracle', category: 'DB', defaultPort: 1521 },
  { code: 'POSTGRESQL', name: 'PostgreSQL', category: 'DB', defaultPort: 5432 },
  { code: 'SQLSERVER', name: 'SQL Server', category: 'DB', defaultPort: 1433 },
  { code: 'DM', name: '达梦', category: 'DB', defaultPort: 5236 },
  { code: 'HIVE', name: 'Hive', category: 'DB', defaultPort: 10000 },
  { code: 'CLICKHOUSE', name: 'ClickHouse', category: 'DB', defaultPort: 8123 },
  { code: 'FTP', name: 'FTP', category: 'FILE', defaultPort: 21 },
  { code: 'SFTP', name: 'SFTP', category: 'FILE', defaultPort: 22 },
  { code: 'OSS', name: 'OSS', category: 'FILE', defaultPort: null },
  { code: 'KAFKA', name: 'Kafka', category: 'MQ', defaultPort: 9092 },
  { code: 'HTTP', name: 'HTTP 接口', category: 'API', defaultPort: null },
  { code: 'WEBSERVICE', name: 'WebService', category: 'API', defaultPort: null }
]

function onTypeChange(code) {
  const t = types.value.find((x) => x.code === code)
  if (t && t.defaultPort) form.port = Number(t.defaultPort)
  else if (DEFAULT_TYPES.find((x) => x.code === code)?.defaultPort) {
    form.port = DEFAULT_TYPES.find((x) => x.code === code).defaultPort
  }
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/datasource/v1/sources', {
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
  query.type = ''
  query.status = ''
  search()
}

function openCreate() {
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(form, emptyForm())
  form.id = row.id
  form.sourceName = nameOf(row)
  form.sourceCode = pick(row, ['sourceCode', 'code'], '')
  form.sourceType = pick(row, ['sourceType', 'type'], 'MYSQL')
  form.deptId = row.deptId ?? null
  form.host = pick(row, ['host', 'ip', 'hostname', 'endpoint'], '')
  form.port = Number(pick(row, ['port'], 0)) || undefined
  form.dbName = pick(row, ['dbName', 'databaseName'], '')
  form.username = pick(row, ['username', 'user'], '')
  form.password = ''
  form.filePath = pick(row, ['filePath', 'path'], '')
  form.apiUrl = pick(row, ['apiUrl', 'url'], '')
  const extra = row.extraConfig
  form.extraConfig = extra == null ? '' : typeof extra === 'string' ? extra : JSON.stringify(extra)
  form.owner = pick(row, ['owner'], '')
  form.secretLevel = Number(pick(row, ['secretLevel'], 2)) || 2
  form.remark = pick(row, ['remark', 'description'], '')
  dialogVisible.value = true
}

function payloadOf() {
  let extraConfig = form.extraConfig
  if (extraConfig) {
    try {
      extraConfig = JSON.parse(extraConfig)
    } catch (e) {
      extraConfig = { note: extraConfig }
    }
  } else {
    extraConfig = null
  }
  const p = {
    sourceName: form.sourceName,
    sourceCode: form.sourceCode,
    sourceType: form.sourceType,
    deptId: form.deptId,
    host: form.host,
    port: form.port,
    dbName: form.dbName,
    username: form.username,
    filePath: form.filePath,
    apiUrl: form.apiUrl,
    extraConfig,
    owner: form.owner,
    secretLevel: form.secretLevel,
    remark: form.remark
  }
  if (form.password) p.password = form.password
  return p
}

async function save() {
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await request.put(`/datasource/v1/sources/${form.id}`, payloadOf())
      ElMessage.success('数据源已更新')
    } else {
      await request.post('/datasource/v1/sources', payloadOf())
      ElMessage.success('数据源已创建')
    }
    dialogVisible.value = false
    load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function test(row) {
  testingId.value = row.id
  try {
    const res = await request.post(`/datasource/v1/sources/${row.id}/test`)
    const d = res.data || {}
    testResult.ok = d.success !== undefined ? !!d.success : !d.error
    testResult.message = pick(d, ['message', 'msg', 'error'], testResult.ok ? '连接成功' : '连接失败')
    testResult.latency = d.latencyMs !== undefined ? `${d.latencyMs} ms` : '-'
    testResult.probe = pick(d, ['probe'], '-')
    testResult.target = pick(d, ['target'], '-')
    testVisible.value = true
  } catch (e) {
    testResult.ok = false
    testResult.message = '测试请求失败，请检查后端服务或数据源配置'
    testResult.latency = '-'
    testResult.probe = '-'
    testResult.target = '-'
    testVisible.value = true
  } finally {
    testingId.value = null
  }
}

async function toggleStatus(row, enabled) {
  statusLoading.value = row.id
  try {
    await request.put(`/datasource/v1/sources/${row.id}/status`, { status: enabled ? 'ENABLED' : 'DISABLED' })
    ElMessage.success(enabled ? '数据源已启用' : '数据源已停用')
    load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    statusLoading.value = null
  }
}

async function openHistory(row) {
  historyVisible.value = true
  history.value = []
  try {
    const res = await request.get(`/datasource/v1/sources/${row.id}/history`, { params: { pageNum: 1, pageSize: 50 } })
    history.value = listOf(res.data)
  } catch (e) {
    history.value = []
  }
}

async function remove(row) {
  const ok = await confirmBox(`确认删除数据源「${nameOf(row)}」？`, '删除数据源', 'error')
  if (!ok) return
  try {
    await request.delete(`/datasource/v1/sources/${row.id}`)
    ElMessage.success('数据源已删除')
    load()
  } catch (e) {
    // 拦截器已提示
  }
}

async function loadTypes() {
  try {
    const res = await request.get('/datasource/v1/types')
    const list = normalizeTypes(res.data)
    types.value = list.length ? list : DEFAULT_TYPES
  } catch (e) {
    types.value = DEFAULT_TYPES
  }
}

async function loadDepts() {
  try {
    const res = await request.get('/user/v1/depts')
    const out = []
    const walk = (list, prefix = '') => {
      list.forEach((d) => {
        out.push({ id: d.id, name: `${prefix}${d.deptName || d.name || ''}` })
        const children = d.children || d.childList || []
        if (children.length) walk(children, `${prefix}　`)
      })
    }
    walk(listOf(res.data))
    depts.value = out
  } catch (e) {
    depts.value = []
  }
}

onMounted(() => {
  load()
  loadTypes()
  loadDepts()
})
</script>
