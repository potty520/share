<template>
  <div class="page-card" v-loading="loading">
    <div class="page-header">
      <div>
        <h3 class="page-title">共享服务</h3>
        <div class="page-subtitle">将目录资源发布为共享 API，管理上下线、QPS 与调用凭据</div>
      </div>
      <div class="toolbar">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建服务</el-button>
        <el-button :icon="Key" @click="openCredentials()">凭据管理</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <el-form inline class="filter-bar" @submit.prevent>
      <el-form-item label="关键词">
        <el-input v-model="query.keyword" placeholder="服务名称 / 编码" clearable @keyup.enter="search" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px">
          <el-option label="已上线" value="ONLINE" />
          <el-option label="已下线" value="OFFLINE" />
          <el-option label="草稿" value="DRAFT" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" stripe border size="small" empty-text="暂无共享服务">
      <el-table-column label="服务名称" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="cell-link" @click="$router.push(`/share-api/${row.id}`)">{{ nameOf(row) }}</span>
          <div class="muted">{{ pick(row, ['apiCode', 'code'], '-') }}</div>
        </template>
      </el-table-column>
      <el-table-column label="绑定目录" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ pick(row, ['catalogName', 'resourceName'], '-') }}</template>
      </el-table-column>
      <el-table-column label="挂接表" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ pick(row, ['sourceTable'], '-') }}</template>
      </el-table-column>
      <el-table-column label="调用路径" min-width="190" show-overflow-tooltip>
        <template #default="{ row }">
          <span style="font-family: Consolas, monospace">{{ pick(row, ['httpMethod'], 'GET') }} /api/open/v1/{{ pick(row, ['apiCode', 'code'], '-') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="QPS 限制" width="95" align="center">
        <template #default="{ row }">{{ pick(row, ['qpsLimit', 'qps'], '-') }}</template>
      </el-table-column>
      <el-table-column label="累计调用量" width="115" align="right">
        <template #default="{ row }">{{ fmtNumber(pick(row, ['callCount', 'totalCalls'], '-')) }}</template>
      </el-table-column>
      <el-table-column label="最近调用" width="155">
        <template #default="{ row }">{{ fmtDateTime(pick(row, ['lastCallTime', 'updateTime'])) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push(`/share-api/${row.id}`)">详情/调试</el-button>
          <el-button v-if="isOnline(row)" link type="warning" @click="toggleOnline(row, false)">下线</el-button>
          <el-button v-else link type="success" @click="toggleOnline(row, true)">上线</el-button>
          <el-button link type="primary" @click="openCredentials(row)">凭据</el-button>
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

    <!-- 新建服务 -->
    <el-dialog v-model="createVisible" title="新建共享服务" width="760px" top="6vh" append-to-body destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="服务名称" prop="apiName">
              <el-input v-model="form.apiName" placeholder="如：企业基础信息查询" maxlength="60" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="服务编码" prop="apiCode">
              <el-input v-model="form.apiCode" placeholder="如：enterprise-info" maxlength="60" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="绑定目录">
              <el-select v-model="form.catalogId" filterable clearable placeholder="选择已发布目录资源" style="width: 100%" @change="onCatalogChange">
                <el-option v-for="c in catalogs" :key="c.id" :label="`${c.name}${c.level ? '（' + c.level + '级）' : ''}`" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="数据表名">
              <el-input v-model="form.sourceTable" placeholder="如：demo_enterprise" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="HTTP 方法">
              <el-select v-model="form.httpMethod" style="width: 100%">
                <el-option label="GET" value="GET" />
                <el-option label="POST" value="POST" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="QPS 限制">
              <el-input-number v-model="form.qpsLimit" :min="1" :max="10000" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="返回字段">
              <el-input v-model="form.returnFields" placeholder="逗号分隔，如：id,name,creditCode" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="脱敏字段">
              <el-input v-model="form.maskFields" placeholder="逗号分隔，如：phone,idCard" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="责任人">
              <el-input v-model="form.owner" placeholder="服务责任人" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="归属部门">
              <el-select v-model="form.deptId" clearable filterable placeholder="选择部门" style="width: 100%">
                <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="服务说明">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="300" show-word-limit />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">请求参数定义</el-divider>
        <el-table :data="form.params" size="small" border>
          <el-table-column label="参数名" width="150">
            <template #default="{ row }"><el-input v-model="row.paramName" size="small" placeholder="name" /></template>
          </el-table-column>
          <el-table-column label="中文名" width="140">
            <template #default="{ row }"><el-input v-model="row.paramDesc" size="small" placeholder="中文名" /></template>
          </el-table-column>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <el-select v-model="row.paramType" size="small">
                <el-option label="字符串" value="STRING" />
                <el-option label="数值" value="NUMBER" />
                <el-option label="布尔" value="BOOLEAN" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="示例值" min-width="120">
            <template #default="{ row }"><el-input v-model="row.exampleValue" size="small" placeholder="示例值" /></template>
          </el-table-column>
          <el-table-column label="必填" width="70" align="center">
            <template #default="{ row }"><el-checkbox v-model="row.required" size="small" /></template>
          </el-table-column>
          <el-table-column label="" width="56" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" :icon="Delete" @click="form.params.splice($index, 1)" />
            </template>
          </el-table-column>
        </el-table>
        <el-button class="mt-8" size="small" :icon="Plus" @click="form.params.push({ paramName: '', paramDesc: '', paramType: 'STRING', required: false, exampleValue: '' })">
          添加参数
        </el-button>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">创建</el-button>
      </template>
    </el-dialog>

    <!-- 凭据管理 -->
    <el-dialog v-model="credVisible" title="调用凭据管理" width="780px" append-to-body>
      <div class="flex-between mb-12">
        <span class="muted">凭据用 AppKey / AppSecret 鉴权，Secret 仅签发时展示一次</span>
        <el-button size="small" type="primary" :icon="Plus" @click="openIssue">签发凭据</el-button>
      </div>
      <el-table :data="credentials" size="small" border stripe empty-text="暂无凭据">
        <el-table-column label="AppKey" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['appKey'], '-') }}</template>
        </el-table-column>
        <el-table-column label="关联服务" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['apiName', 'apiCode'], '-') }}</template>
        </el-table-column>
        <el-table-column label="使用方" width="120">
          <template #default="{ row }">{{ pick(row, ['consumerName'], '-') }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status || 'ACTIVE')">{{ credStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="到期时间" width="160">
          <template #default="{ row }">{{ fmtDateTime(pick(row, ['expireTime'], '长期有效')) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" :disabled="isRevoked(row)" @click="revokeCredential(row)">吊销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 签发凭据 -->
    <el-dialog v-model="issueVisible" title="签发调用凭据" width="520px" append-to-body>
      <el-form label-width="96px">
        <el-form-item label="服务">
          <el-select v-model="issueForm.apiId" filterable placeholder="选择服务" style="width: 100%">
            <el-option v-for="a in apiOptions" :key="a.id" :label="pick(a, ['apiName', 'name'], `服务 #${a.id}`)" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="使用方用户">
          <el-select v-model="issueForm.consumerId" filterable remote :remote-method="searchUsers" placeholder="搜索用户（可空）" clearable style="width: 100%">
            <el-option v-for="u in userOptions" :key="u.id" :label="`${u.realName || u.username}（${u.username}）`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="有效天数">
          <el-input-number v-model="issueForm.expireDays" :min="1" :max="3650" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueVisible = false">取消</el-button>
        <el-button type="primary" :loading="issuing" @click="issueCredential">签发</el-button>
      </template>
    </el-dialog>

    <!-- 新签发凭据结果 -->
    <el-dialog v-model="secretVisible" title="凭据签发成功" width="540px" append-to-body>
      <el-alert type="warning" :closable="false" show-icon title="AppSecret 仅展示一次，请立即保存" />
      <el-descriptions :column="1" border size="small" class="mt-12">
        <el-descriptions-item label="AppKey">{{ newCredential.appKey }}</el-descriptions-item>
        <el-descriptions-item label="AppSecret">{{ newCredential.appSecret }}</el-descriptions-item>
        <el-descriptions-item label="使用方">{{ newCredential.consumerName }}</el-descriptions-item>
      </el-descriptions>
      <template #footer><el-button type="primary" @click="secretVisible = false">已保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search, RefreshLeft, Key, Delete } from '@element-plus/icons-vue'
import request from '@/api/request'
import { clean, confirmBox, fmtDateTime, fmtNumber, listOf, pageOf, pick, tagType } from '@/api/helpers'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const query = reactive({ keyword: '', status: '' })
const catalogs = ref([])
const depts = ref([])
const apiOptions = ref([])

const createVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive(emptyForm())
const formRules = {
  apiName: [{ required: true, message: '请输入服务名称', trigger: 'blur' }],
  apiCode: [{ required: true, message: '请输入服务编码', trigger: 'blur' }]
}

const credVisible = ref(false)
const credentials = ref([])
const currentApi = ref(null)
const issueVisible = ref(false)
const issuing = ref(false)
const issueForm = reactive({ apiId: null, consumerId: null, expireDays: 90 })
const userOptions = ref([])
const secretVisible = ref(false)
const newCredential = reactive({ appKey: '', appSecret: '', consumerName: '' })

const STATUS_TEXT = { ONLINE: '已上线', OFFLINE: '已下线', DRAFT: '草稿', DISABLED: '已禁用' }
const CRED_STATUS = { ACTIVE: '生效中', ENABLED: '生效中', REVOKED: '已吊销', EXPIRED: '已过期', DISABLED: '已禁用' }

function emptyForm() {
  return {
    apiName: '',
    apiCode: '',
    catalogId: null,
    catalogName: '',
    sourceTable: '',
    httpMethod: 'GET',
    qpsLimit: 100,
    returnFields: '',
    maskFields: '',
    owner: '',
    deptId: null,
    remark: '',
    params: [{ paramName: '', paramDesc: '', paramType: 'STRING', required: false, exampleValue: '' }]
  }
}

function nameOf(row) {
  return pick(row, ['apiName', 'name'], `服务 #${row?.id ?? ''}`)
}

function statusText(v) {
  return STATUS_TEXT[String(v || '').toUpperCase()] || v || '-'
}

function credStatusText(v) {
  return CRED_STATUS[String(v || 'ACTIVE').toUpperCase()] || v || '-'
}

function isOnline(row) {
  return String(row.status || '').toUpperCase() === 'ONLINE'
}

function isRevoked(row) {
  return ['REVOKED', 'EXPIRED', 'DISABLED'].includes(String(row.status || '').toUpperCase())
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/share/v1/apis', {
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

function openCreate() {
  Object.assign(form, emptyForm())
  createVisible.value = true
}

function onCatalogChange(id) {
  const c = catalogs.value.find((x) => x.id === id)
  if (c) form.catalogName = c.name
}

function paramPayload() {
  return form.params
    .filter((p) => p.paramName)
    .map((p) => ({
      name: p.paramName,
      paramName: p.paramName,
      type: p.paramType,
      paramType: p.paramType,
      desc: p.paramDesc,
      paramDesc: p.paramDesc,
      required: !!p.required,
      example: p.exampleValue,
      exampleValue: p.exampleValue
    }))
}

async function save() {
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    const payload = {
      apiName: form.apiName,
      apiCode: form.apiCode,
      catalogId: form.catalogId,
      catalogName: form.catalogName,
      sourceTable: form.sourceTable,
      httpMethod: form.httpMethod,
      paramsJson: JSON.stringify(paramPayload()),
      returnFields: form.returnFields,
      maskFields: form.maskFields,
      qpsLimit: form.qpsLimit,
      owner: form.owner,
      deptId: form.deptId,
      remark: form.remark
    }
    await request.post('/share/v1/apis', payload)
    ElMessage.success('共享服务已创建（草稿），上线后可对外调用')
    createVisible.value = false
    load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function toggleOnline(row, online) {
  const ok = await confirmBox(
    online ? `确认上线服务「${nameOf(row)}」？上线后可按凭据调用。` : `确认下线服务「${nameOf(row)}」？下线后调用将不可用。`,
    online ? '上线服务' : '下线服务'
  )
  if (!ok) return
  try {
    await request.post(`/share/v1/apis/${row.id}/${online ? 'online' : 'offline'}`)
    ElMessage.success(online ? '服务已上线' : '服务已下线')
    load()
  } catch (e) {
    // 拦截器已提示
  }
}

async function openCredentials(row) {
  currentApi.value = row || null
  credVisible.value = true
  credentials.value = []
  try {
    const res = await request.get('/share/v1/credentials', {
      params: row ? { apiId: row.id, pageNum: 1, pageSize: 100 } : { pageNum: 1, pageSize: 100 }
    })
    credentials.value = listOf(res.data)
  } catch (e) {
    credentials.value = []
  }
}

function openIssue() {
  issueForm.apiId = currentApi.value?.id ?? null
  issueForm.consumerId = null
  issueForm.expireDays = 90
  issueVisible.value = true
  if (!apiOptions.value.length) loadApiOptions()
  if (!userOptions.value.length) searchUsers('')
}

async function searchUsers(keyword) {
  try {
    const res = await request.get('/user/v1/users', { params: { pageNum: 1, pageSize: 20, keyword: keyword || undefined } })
    userOptions.value = listOf(res.data)
  } catch (e) {
    userOptions.value = []
  }
}

async function issueCredential() {
  if (!issueForm.apiId) {
    ElMessage.warning('请选择要签发的服务')
    return
  }
  issuing.value = true
  try {
    const user = userOptions.value.find((u) => u.id === issueForm.consumerId)
    const res = await request.post('/share/v1/credentials', {
      apiId: issueForm.apiId,
      consumerId: issueForm.consumerId || undefined,
      consumerName: user ? user.realName || user.username : undefined,
      expireDays: issueForm.expireDays
    })
    const d = res.data || {}
    newCredential.appKey = pick(d, ['appKey', 'appId', 'apiKey'], '-')
    newCredential.appSecret = pick(d, ['appSecret', 'secret', 'apiSecret'], '-')
    newCredential.consumerName = pick(d, ['consumerName'], '-')
    issueVisible.value = false
    secretVisible.value = true
    openCredentials(currentApi.value)
  } catch (e) {
    // 拦截器已提示
  } finally {
    issuing.value = false
  }
}

async function revokeCredential(row) {
  const ok = await confirmBox('确认吊销该凭据？吊销后使用该凭据的调用将立即失败。', '吊销凭据', 'error')
  if (!ok) return
  try {
    await request.put(`/share/v1/credentials/${row.id}/revoke`, { reason: '管理员手工吊销' })
    ElMessage.success('凭据已吊销')
    openCredentials(currentApi.value)
  } catch (e) {
    // 拦截器已提示
  }
}

async function loadCatalogs() {
  try {
    const res = await request.get('/catalog/v1/resources', { params: { pageNum: 1, pageSize: 100 } })
    catalogs.value = listOf(res.data)
      .filter((c) => String(c.status || '').toUpperCase() === 'PUBLISHED')
      .map((c) => ({ id: c.id, name: pick(c, ['resourceName', 'name'], `资源 #${c.id}`), level: pick(c, ['secretLevel', 'level'], '') }))
  } catch (e) {
    catalogs.value = []
  }
}

async function loadDepts() {
  try {
    const res = await request.get('/user/v1/depts')
    const out = []
    const walk = (list, prefix = '') => {
      list.forEach((d) => {
        out.push({ id: d.id, name: `${prefix}${d.deptName || d.name || ''}` })
        const children = d.children || []
        if (children.length) walk(children, `${prefix}　`)
      })
    }
    walk(listOf(res.data))
    depts.value = out
  } catch (e) {
    depts.value = []
  }
}

async function loadApiOptions() {
  try {
    const res = await request.get('/share/v1/apis', { params: { pageNum: 1, pageSize: 100 } })
    apiOptions.value = listOf(res.data)
  } catch (e) {
    apiOptions.value = []
  }
}

onMounted(() => {
  load()
  loadCatalogs()
  loadDepts()
  loadApiOptions()
})
</script>
