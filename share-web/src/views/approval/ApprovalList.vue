<template>
  <div class="page-card" v-loading="loading">
    <div class="page-header">
      <div>
        <h3 class="page-title">申请审批</h3>
        <div class="page-subtitle">数据共享申请、多级审批、授权期限与回收管理</div>
      </div>
      <div class="toolbar">
        <el-button type="primary" :icon="Plus" @click="openApply">发起申请</el-button>
        <el-button :icon="Refresh" @click="reload">刷新</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane label="我的申请" name="mine">
        <el-table :data="mineRows" stripe border size="small" empty-text="暂无申请记录">
          <el-table-column label="申请编号" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['applyNo', 'id'], '-') }}</template>
          </el-table-column>
          <el-table-column label="申请资源" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ catalogOf(row) }}</template>
          </el-table-column>
          <el-table-column label="用途" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['purpose'], '-') }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(row.status)">{{ applyStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前节点" width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['currentNodeName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="申请时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['createTime', 'applyTime'])) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="isPending(row)" link type="warning" @click="withdraw(row)">撤回</el-button>
              <el-button v-if="isApproved(row)" link type="success" @click="openRenew(row)">续期</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="minePage"
            :page-size="pageSize"
            background
            layout="total, prev, pager, next"
            :total="mineTotal"
            @current-change="loadMine"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="待办审批" name="todo">
        <el-table :data="todoRows" stripe border size="small" empty-text="暂无待办审批">
          <el-table-column label="申请编号" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['applyNo', 'id'], '-') }}</template>
          </el-table-column>
          <el-table-column label="申请资源" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ catalogOf(row) }}</template>
          </el-table-column>
          <el-table-column label="申请人" width="120">
            <template #default="{ row }">{{ pick(row, ['applicantName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="用途" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['purpose'], '-') }}</template>
          </el-table-column>
          <el-table-column label="密级" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="Number(row.level) >= 3 ? 'warning' : 'info'">{{ row.level || '-' }} 级</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前节点" width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['currentNodeName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="提交时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['createTime', 'applyTime'])) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="success" @click="openAudit(row, 'approve')">通过</el-button>
              <el-button link type="danger" @click="openAudit(row, 'reject')">驳回</el-button>
              <el-button link type="warning" @click="openAudit(row, 'transfer')">转办</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="todoPage"
            :page-size="pageSize"
            background
            layout="total, prev, pager, next"
            :total="todoTotal"
            @current-change="loadTodo"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="授权列表" name="grants">
        <el-table :data="grants" stripe border size="small" empty-text="暂无授权记录">
          <el-table-column label="授权编号" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['grantNo', 'id'], '-') }}</template>
          </el-table-column>
          <el-table-column label="授权资源" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['catalogName', 'resourceName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="被授权人" width="130">
            <template #default="{ row }">{{ pick(row, ['consumerName', 'userName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="授权时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['startTime', 'createTime'])) }}</template>
          </el-table-column>
          <el-table-column label="到期时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['endTime', 'expireTime'])) }}</template>
          </el-table-column>
          <el-table-column label="剩余天数" width="100" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.remainDays !== undefined" size="small" :type="Number(row.remainDays) <= 7 ? 'danger' : 'info'">
                {{ row.remainDays }} 天
              </el-tag>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="grantTag(row)">{{ grantStatusText(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" :disabled="!isActiveGrant(row)" @click="revokeGrant(row)">吊销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 发起申请 -->
    <el-dialog v-model="applyVisible" title="发起数据共享申请" width="680px" append-to-body destroy-on-close>
      <el-form ref="applyFormRef" :model="applyForm" :rules="applyRules" label-width="96px">
        <el-form-item label="资源检索">
          <el-input v-model="catalogKeyword" placeholder="输入关键词检索已发布资源" clearable @keyup.enter="searchCatalogs(catalogKeyword)">
            <template #append><el-button :icon="Search" @click="searchCatalogs(catalogKeyword)">检索</el-button></template>
          </el-input>
        </el-form-item>
        <el-form-item label="选择资源" prop="catalogId">
          <el-select v-model="applyForm.catalogId" filterable remote :remote-method="searchCatalogs" placeholder="请选择要申请的数据资源" style="width: 100%">
            <el-option v-for="c in catalogOptions" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请用途" prop="purpose">
          <el-input v-model="applyForm.purpose" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="说明数据使用场景，便于审批人判断" />
        </el-form-item>
        <el-form-item label="使用范围" prop="scope">
          <el-input v-model="applyForm.scope" placeholder="如：本部门业务分析 / 跨部门联合监管" />
        </el-form-item>
        <el-form-item label="使用期限">
          <el-select v-model="applyForm.expireDays" style="width: 200px">
            <el-option label="7 天" :value="7" />
            <el-option label="30 天" :value="30" />
            <el-option label="90 天" :value="90" />
            <el-option label="180 天" :value="180" />
            <el-option label="365 天" :value="365" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- 审批动作 -->
    <el-dialog v-model="auditVisible" :title="auditTitle" width="540px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="申请资源">
          <span>{{ auditRow ? catalogOf(auditRow) : '' }}</span>
        </el-form-item>
        <el-form-item v-if="auditMode === 'transfer'" label="转办给">
          <el-select v-model="auditForm.toUserId" filterable remote :remote-method="searchUsers" placeholder="搜索用户" style="width: 100%">
            <el-option v-for="u in userOptions" :key="u.id" :label="`${u.realName || u.username}（${u.username}）`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="审批意见">
          <el-input v-model="auditForm.comment" type="textarea" :rows="3" maxlength="300" show-word-limit :placeholder="auditMode === 'reject' ? '驳回必须填写审批意见' : '请填写审批意见'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditVisible = false">取消</el-button>
        <el-button type="primary" :loading="auditing" @click="submitAudit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 续期 -->
    <el-dialog v-model="renewVisible" title="申请续期" width="480px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="申请资源">
          <span>{{ renewRow ? catalogOf(renewRow) : '' }}</span>
        </el-form-item>
        <el-form-item label="续期时长">
          <el-select v-model="renewDays" style="width: 100%">
            <el-option label="7 天" :value="7" />
            <el-option label="30 天" :value="30" />
            <el-option label="90 天" :value="90" />
            <el-option label="180 天" :value="180" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renewVisible = false">取消</el-button>
        <el-button type="primary" :loading="renewing" @click="submitRenew">提交续期</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailVisible" title="申请详情" size="700px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="申请编号">{{ pick(detailRow, ['applyNo', 'id'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="tagType(detailRow.status)">{{ applyStatusText(detailRow.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="申请资源">{{ catalogOf(detailRow) }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ pick(detailRow, ['applicantName'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="使用期限">
          {{ pick(detailRow, ['startDate'], '-') }} ~ {{ pick(detailRow, ['endDate'], '-') }}
        </el-descriptions-item>
        <el-descriptions-item label="数据密级">{{ pick(detailRow, ['level'], '-') }} 级{{ detailRow.sensitive ? '（涉敏）' : '' }}</el-descriptions-item>
        <el-descriptions-item label="当前节点">{{ pick(detailRow, ['currentNodeName'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="申请时间">{{ fmtDateTime(pick(detailRow, ['createTime'])) }}</el-descriptions-item>
        <el-descriptions-item label="用途" :span="2">{{ pick(detailRow, ['purpose'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="使用范围" :span="2">{{ pick(detailRow, ['scope'], '-') }}</el-descriptions-item>
      </el-descriptions>
      <h4 class="section-title mt-12">审批轨迹</h4>
      <el-timeline v-if="flowNodes.length">
        <el-timeline-item
          v-for="(n, i) in flowNodes"
          :key="n.id || i"
          :timestamp="fmtDateTime(pick(n, ['handleTime', 'createTime']))"
          :type="nodeType(n)"
          placement="top"
        >
          <div class="flex-gap">
            <strong>{{ pick(n, ['nodeName', 'name'], `节点 ${i + 1}`) }}</strong>
            <el-tag size="small" :type="tagType(n.status)">{{ nodeStatusText(n.status) }}</el-tag>
            <span class="muted">{{ pick(n, ['roleCode', 'assigneeName'], '') }}</span>
          </div>
          <div class="muted" style="margin-top: 4px">
            处理人：{{ pick(n, ['approverName', 'handlerName', 'assigneeName'], '-') }} ·
            意见：{{ pick(n, ['comment', 'opinion'], '无') }}
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无审批轨迹" :image-size="80" />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import request from '@/api/request'
import { confirmBox, fmtDateTime, listOf, pick, tagType } from '@/api/helpers'

const route = useRoute()
const loading = ref(false)
const tab = ref('mine')
const pageSize = 10

const mineRows = ref([])
const minePage = ref(1)
const mineTotal = ref(0)
const todoRows = ref([])
const todoPage = ref(1)
const todoTotal = ref(0)
const grants = ref([])

const applyVisible = ref(false)
const submitting = ref(false)
const applyFormRef = ref()
const applyForm = reactive({ catalogId: null, purpose: '', scope: '', expireDays: 30 })
const catalogKeyword = ref('')
const catalogOptions = ref([])
const applyRules = {
  catalogId: [{ required: true, message: '请选择申请的数据资源', trigger: 'change' }],
  purpose: [{ required: true, message: '请填写申请用途', trigger: 'blur' }],
  scope: [{ required: true, message: '请填写使用范围', trigger: 'blur' }]
}

const auditVisible = ref(false)
const auditing = ref(false)
const auditMode = ref('approve')
const auditRow = ref(null)
const auditForm = reactive({ comment: '', toUserId: null })
const userOptions = ref([])

const renewVisible = ref(false)
const renewRow = ref(null)
const renewDays = ref(30)
const renewing = ref(false)

const detailVisible = ref(false)
const detailRow = ref({})
const flowNodes = ref([])

const APPLY_STATUS = {
  PENDING: '审批中',
  APPROVING: '审批中',
  APPROVED: '已通过',
  PASSED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
  EXPIRED: '已过期',
  REVOKED: '已回收',
  DRAFT: '草稿'
}
const NODE_STATUS = { PENDING: '待处理', APPROVED: '已通过', REJECTED: '已驳回', WAITING: '等待中', SKIPPED: '已跳过', RUNNING: '处理中' }
const GRANT_STATUS = { ACTIVE: '生效中', VALID: '生效中', EXPIRED: '已过期', REVOKED: '已吊销', PENDING: '待生效' }

const auditTitle = computed(() => {
  if (auditMode.value === 'approve') return '审批通过'
  if (auditMode.value === 'reject') return '驳回申请'
  return '转办审批'
})

function applyStatusText(v) {
  return APPLY_STATUS[String(v || '').toUpperCase()] || v || '-'
}

function nodeStatusText(v) {
  return NODE_STATUS[String(v || '').toUpperCase()] || v || '-'
}

function grantStatusText(row) {
  const s = String(row.effectiveStatus || row.status || '').toUpperCase()
  return GRANT_STATUS[s] || s || '-'
}

function grantTag(row) {
  return tagType(row.effectiveStatus || row.status || '')
}

function isPending(row) {
  return ['PENDING', 'APPROVING'].includes(String(row.status || '').toUpperCase())
}

function isApproved(row) {
  return ['APPROVED', 'PASSED'].includes(String(row.status || '').toUpperCase())
}

function isActiveGrant(row) {
  const s = String(row.effectiveStatus || row.status || '').toUpperCase()
  if (['REVOKED', 'EXPIRED'].includes(s)) return false
  const expire = pick(row, ['endTime', 'expireTime', 'expireAt'], '')
  if (expire) {
    const t = new Date(String(expire).replace(' ', 'T')).getTime()
    if (!Number.isNaN(t) && t < Date.now()) return false
  }
  return true
}

function catalogOf(row) {
  const name = pick(row, ['catalogName', 'resourceName', 'apiName'], '')
  if (name) return name
  return row?.catalogId ? `目录 #${row.catalogId}` : '-'
}

function nodeType(n) {
  const s = String(n.status || '').toUpperCase()
  if (['APPROVED', 'PASSED', 'SUCCESS'].includes(s)) return 'success'
  if (['REJECTED', 'FAILED'].includes(s)) return 'danger'
  if (['PENDING', 'RUNNING'].includes(s)) return 'primary'
  return 'info'
}

async function loadMine() {
  loading.value = true
  try {
    const res = await request.get('/approval/v1/applies', { params: { pageNum: minePage.value, pageSize, mine: true } })
    mineRows.value = listOf(res.data)
    mineTotal.value = res.page?.total ?? mineRows.value.length
  } catch (e) {
    mineRows.value = []
    mineTotal.value = 0
  } finally {
    loading.value = false
  }
}

async function loadTodo() {
  try {
    const res = await request.get('/approval/v1/applies', { params: { pageNum: todoPage.value, pageSize, todo: true } })
    todoRows.value = listOf(res.data)
    todoTotal.value = res.page?.total ?? todoRows.value.length
  } catch (e) {
    todoRows.value = []
    todoTotal.value = 0
  }
}

async function loadGrants() {
  try {
    const res = await request.get('/approval/v1/grants', { params: { pageNum: 1, pageSize: 100 } })
    grants.value = listOf(res.data)
  } catch (e) {
    grants.value = []
  }
}

function reload() {
  loadMine()
  loadTodo()
  loadGrants()
}

function onTab(name) {
  if (name === 'mine') loadMine()
  if (name === 'todo') loadTodo()
  if (name === 'grants') loadGrants()
}

async function searchCatalogs(keyword) {
  if (typeof keyword === 'string') catalogKeyword.value = keyword
  try {
    const res = await request.get('/catalog/v1/resources', {
      params: { pageNum: 1, pageSize: 30, keyword: catalogKeyword.value || undefined }
    })
    catalogOptions.value = listOf(res.data)
      .filter((c) => String(c.status || '').toUpperCase() === 'PUBLISHED')
      .map((c) => ({ id: c.id, name: pick(c, ['resourceName', 'name'], `资源 #${c.id}`) }))
  } catch (e) {
    catalogOptions.value = []
  }
}

async function searchUsers(keyword) {
  try {
    const res = await request.get('/user/v1/users', { params: { pageNum: 1, pageSize: 20, keyword: keyword || undefined } })
    userOptions.value = listOf(res.data)
  } catch (e) {
    userOptions.value = []
  }
}

function openApply() {
  Object.assign(applyForm, { catalogId: null, purpose: '', scope: '', expireDays: 30 })
  catalogKeyword.value = ''
  applyVisible.value = true
  searchCatalogs('')
}

async function submitApply() {
  try {
    await applyFormRef.value.validate()
  } catch (e) {
    return
  }
  submitting.value = true
  try {
    const today = new Date()
    const end = new Date(Date.now() + applyForm.expireDays * 86400000)
    const iso = (d) => d.toISOString().slice(0, 10)
    await request.post('/approval/v1/applies', {
      catalogId: Number(applyForm.catalogId) || applyForm.catalogId,
      purpose: applyForm.purpose,
      scope: applyForm.scope,
      startDate: iso(today),
      endDate: iso(end)
    })
    ElMessage.success('申请已提交，可在「我的申请」查看进度')
    applyVisible.value = false
    tab.value = 'mine'
    loadMine()
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

function openAudit(row, mode) {
  auditRow.value = row
  auditMode.value = mode
  auditForm.comment = ''
  auditForm.toUserId = null
  auditVisible.value = true
  if (mode === 'transfer' && !userOptions.value.length) searchUsers('')
}

async function submitAudit() {
  if (!auditRow.value) return
  if (auditMode.value === 'transfer' && !auditForm.toUserId) {
    ElMessage.warning('请选择转办接收人')
    return
  }
  if (auditMode.value === 'reject' && !auditForm.comment) {
    ElMessage.warning('驳回必须填写审批意见')
    return
  }
  auditing.value = true
  try {
    const id = auditRow.value.id
    if (auditMode.value === 'approve') {
      await request.post(`/approval/v1/applies/${id}/approve`, { comment: auditForm.comment })
      ElMessage.success('审批已通过')
    } else if (auditMode.value === 'reject') {
      await request.post(`/approval/v1/applies/${id}/reject`, { comment: auditForm.comment })
      ElMessage.success('申请已驳回')
    } else {
      const user = userOptions.value.find((u) => u.id === auditForm.toUserId)
      await request.post(`/approval/v1/applies/${id}/transfer`, {
        comment: auditForm.comment,
        toUserId: auditForm.toUserId,
        toUserName: user ? user.realName || user.username : undefined
      })
      ElMessage.success('已转办')
    }
    auditVisible.value = false
    loadTodo()
    loadMine()
  } catch (e) {
    // 拦截器已提示
  } finally {
    auditing.value = false
  }
}

async function withdraw(row) {
  const ok = await confirmBox('确认撤回该申请？', '撤回申请')
  if (!ok) return
  try {
    await request.post(`/approval/v1/applies/${row.id}/withdraw`)
    ElMessage.success('申请已撤回')
    loadMine()
  } catch (e) {
    // 拦截器已提示
  }
}

function openRenew(row) {
  renewRow.value = row
  renewDays.value = 30
  renewVisible.value = true
}

async function submitRenew() {
  if (!renewRow.value) return
  renewing.value = true
  try {
    const base = new Date()
    const currentEnd = new Date(String(pick(renewRow.value, ['endDate'], '') || '').replace(' ', 'T')).getTime()
    const from = !Number.isNaN(currentEnd) && currentEnd > base.getTime() ? currentEnd : base.getTime()
    const newEnd = new Date(from + renewDays.value * 86400000).toISOString().slice(0, 10)
    await request.post(`/approval/v1/applies/${renewRow.value.id}/renew`, { newEndDate: newEnd })
    ElMessage.success('续期申请已提交')
    renewVisible.value = false
    loadMine()
  } catch (e) {
    // 拦截器已提示
  } finally {
    renewing.value = false
  }
}

async function openDetail(row) {
  detailRow.value = row
  flowNodes.value = []
  detailVisible.value = true
  try {
    const res = await request.get(`/approval/v1/applies/${row.id}`)
    const d = res.data || {}
    detailRow.value = d
    flowNodes.value = listOf(d.nodes || [])
  } catch (e) {
    // 保留基础信息
  }
}

async function revokeGrant(row) {
  const ok = await confirmBox('确认吊销该授权？吊销后使用方将无法继续访问。', '吊销授权', 'error')
  if (!ok) return
  try {
    await request.post(`/approval/v1/grants/${pick(row, ['id', 'grantId'])}/revoke`, { reason: '管理员手工吊销' })
    ElMessage.success('授权已吊销')
    loadGrants()
  } catch (e) {
    // 拦截器已提示
  }
}

onMounted(() => {
  reload()
  const { action, catalogId, catalogName } = route.query
  if (action === 'apply') {
    openApply()
    if (catalogId) {
      applyForm.catalogId = Number(catalogId) || catalogId
      if (catalogName) catalogOptions.value = [{ id: applyForm.catalogId, name: String(catalogName) }]
    }
  }
})
</script>
