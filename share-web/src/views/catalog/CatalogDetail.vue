<template>
  <div v-loading="loading">
    <div v-if="!detail && !loading">
      <el-result icon="warning" title="资源不存在或无权访问" sub-title="请返回数据目录重新选择">
        <template #extra>
          <el-button type="primary" @click="$router.push('/catalog')">返回数据目录</el-button>
        </template>
      </el-result>
    </div>

    <template v-else-if="detail">
      <div class="page-card">
        <div class="detail-hero">
          <div>
            <h3 class="detail-title">{{ nameOf }}</h3>
            <div class="tag-list">
              <el-tag :type="tagType(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag>
              <el-tag :type="shareTypeTag(detail.shareType)" size="small" effect="plain">
                {{ shareTypeText(detail.shareType) }}
              </el-tag>
              <el-tag :type="levelTag(levelOf)" size="small">密级 {{ levelText(levelOf) }}</el-tag>
              <el-tag v-if="classifyName" size="small" effect="plain" type="info">{{ classifyName }}</el-tag>
              <el-tag v-for="t in tags" :key="t" size="small" effect="plain">{{ t }}</el-tag>
            </div>
            <p class="muted" style="margin: 10px 0 0; max-width: 860px">
              {{ pick(detail, ['summary', 'description', 'remark'], '暂无资源描述') }}
            </p>
          </div>
          <div class="toolbar">
            <el-button :icon="Back" @click="$router.back()">返回</el-button>
            <el-button type="primary" :disabled="!canApply" @click="openApply">申请共享</el-button>
            <el-button v-if="canManage" plain @click="$router.push('/catalog-manage')">编目维护</el-button>
          </div>
        </div>

        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="资源编码">{{ pick(detail, ['resourceCode', 'code', 'catalogCode'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="提供部门">{{ pick(detail, ['deptName', 'departmentName'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="提供方联系人">{{ pick(detail, ['providerContact', 'contactName'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="数据来源类型">{{ sourceTypeText }}</el-descriptions-item>
          <el-descriptions-item label="挂接位置" :span="2">{{ attachPath }}</el-descriptions-item>
          <el-descriptions-item label="更新周期">{{ pick(detail, ['updateFrequency', 'updateCycle'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ fmtDateTime(pick(detail, ['publishTime', 'publishedAt', 'updateTime'])) }}</el-descriptions-item>
          <el-descriptions-item label="版本号">{{ pick(detail, ['versionNo', 'version', 'currentVersion'], '-') }}</el-descriptions-item>
          <el-descriptions-item label="数据项数">{{ pick(detail, ['itemCount'], items.length) }}</el-descriptions-item>
          <el-descriptions-item label="共享条件" :span="2">{{ shareTypeText(detail.shareType) }}（按平台共享审批流程办理）</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="page-card">
        <el-tabs v-model="tab">
          <el-tab-pane label="数据项" name="items">
            <el-table :data="items" stripe border size="small" empty-text="暂无数据项定义">
              <el-table-column type="index" label="#" width="50" />
              <el-table-column label="字段名" min-width="150">
                <template #default="{ row }">{{ pick(row, ['fieldName', 'name', 'columnName', 'code'], '-') }}</template>
              </el-table-column>
              <el-table-column label="中文名称" min-width="140">
                <template #default="{ row }">{{ pick(row, ['fieldComment', 'fieldLabel', 'label', 'comment'], '-') }}</template>
              </el-table-column>
              <el-table-column label="类型" width="110">
                <template #default="{ row }">{{ pick(row, ['fieldType', 'type', 'dataType'], '-') }}</template>
              </el-table-column>
              <el-table-column label="敏感字段" width="100">
                <template #default="{ row }">
                  <el-tag v-if="isSensitive(row)" size="small" type="warning">敏感</el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="脱敏规则" min-width="130">
                <template #default="{ row }">{{ pick(row, ['maskRuleCode', 'maskRuleName', 'maskType'], '-') }}</template>
              </el-table-column>
              <el-table-column label="样例值" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ pick(row, ['sampleValue'], '-') }}</template>
              </el-table-column>
              <el-table-column label="说明" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ pick(row, ['description', 'remark'], '-') }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="版本记录" name="versions">
            <el-timeline v-if="versions.length">
              <el-timeline-item
                v-for="v in versions"
                :key="v.id || v.versionNo"
                :timestamp="fmtDateTime(pick(v, ['createTime', 'publishTime']))"
                placement="top"
              >
                <div class="flex-gap">
                  <strong>版本 v{{ pick(v, ['versionNo', 'version'], '-') }}</strong>
                  <el-tag v-if="v.changeType" size="small" effect="plain">{{ v.changeType }}</el-tag>
                  <span class="muted">操作人：{{ pick(v, ['operator', 'createBy'], '-') }}</span>
                </div>
                <div class="muted" style="margin-top: 4px">
                  {{ pick(v, ['changeLog', 'remark', 'description'], '无变更说明') }}
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else description="暂无版本记录" :image-size="80" />
          </el-tab-pane>

          <el-tab-pane label="样例数据" name="preview">
            <div class="flex-between mb-12">
              <span class="muted">样例数据按脱敏规则处理，最多展示前 {{ previewLimit }} 行</span>
              <el-button size="small" :icon="Refresh" @click="loadPreview">刷新样例</el-button>
            </div>
            <el-table v-if="previewColumns.length" :data="previewRows" stripe border size="small" max-height="420">
              <el-table-column
                v-for="col in previewColumns"
                :key="col"
                :prop="col"
                :label="col"
                min-width="130"
                show-overflow-tooltip
              />
            </el-table>
            <el-empty v-else description="暂无样例数据（数据源不可达或尚未挂接）" :image-size="90" />
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>

    <!-- 申请共享 -->
    <el-dialog v-model="applyVisible" title="提交共享申请" width="560px" append-to-body>
      <el-form ref="applyFormRef" :model="applyForm" :rules="applyRules" label-width="90px">
        <el-form-item label="申请资源">
          <el-input :model-value="nameOf" disabled />
        </el-form-item>
        <el-form-item label="申请用途" prop="purpose">
          <el-input v-model="applyForm.purpose" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="请说明数据用途，便于审批人判断" />
        </el-form-item>
        <el-form-item label="使用范围" prop="scope">
          <el-input v-model="applyForm.scope" placeholder="如：本部门业务分析 / 跨部门联合监管" />
        </el-form-item>
        <el-form-item label="申请期限" prop="expireDays">
          <el-select v-model="applyForm.expireDays" style="width: 100%">
            <el-option label="7 天" :value="7" />
            <el-option label="30 天" :value="30" />
            <el-option label="90 天" :value="90" />
            <el-option label="180 天" :value="180" />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="isSensitiveCatalog"
          type="warning"
          :closable="false"
          show-icon
          title="该资源为涉敏资源，审批流程将增加安全管理员节点"
        />
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :loading="applying" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Back, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import request from '@/api/request'
import { useAuthStore } from '@/store/auth'
import { fmtDateTime, listOf, pick, tagType } from '@/api/helpers'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const id = computed(() => route.params.id)
const loading = ref(false)
const detail = ref(null)
const items = ref([])
const versions = ref([])
const tab = ref('items')
const applyVisible = ref(false)
const applying = ref(false)
const applyFormRef = ref()
const previewColumns = ref([])
const previewRows = ref([])
const previewLimit = 20

const applyForm = reactive({ purpose: '', scope: '', expireDays: 30 })
const applyRules = {
  purpose: [{ required: true, message: '请填写申请用途', trigger: 'blur' }],
  scope: [{ required: true, message: '请填写使用范围', trigger: 'blur' }]
}

const STATUS_TEXT = { DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', OFFLINE: '已下架' }
const nameOf = computed(() => pick(detail.value, ['resourceName', 'name', 'title', 'catalogName'], `资源 #${id.value}`))
const classifyName = computed(() => pick(detail.value, ['classifyName', 'classify', 'categoryName'], ''))
const levelOf = computed(() => pick(detail.value, ['secretLevel', 'level'], ''))
const tags = computed(() => {
  const t = pick(detail.value, ['tagList', 'tags', 'tagNames'], [])
  if (Array.isArray(t)) return t.map((x) => (typeof x === 'string' ? x : pick(x, ['name', 'tagName'], ''))).filter(Boolean)
  return String(t || '').split(',').filter(Boolean)
})
const canApply = computed(() => auth.hasPerm('approval:apply') && String(detail.value?.status || '').toUpperCase() === 'PUBLISHED')
const canManage = computed(() => auth.hasPerm('catalog:write'))
const isSensitiveCatalog = computed(() => {
  const d = detail.value || {}
  return d.sensitive === true || Number(levelOf.value) >= 3
})
const attach = computed(() => (detail.value && (detail.value.attach || {})) || {})
const sourceTypeText = computed(() => {
  const a = attach.value
  const name = pick(a, ['sourceName', 'dataSourceName'], '')
  const typeName = pick(a, ['sourceTypeName', 'sourceType'], '')
  if (name) return `数据源：${name}`
  if (typeName) return typeName
  const mode = pick(a, ['mode'], '')
  if (mode === 'FILE') return '文件'
  if (mode === 'API') return '接口'
  return pick(detail.value, ['sourceTypeName', 'sourceType'], '-')
})
const attachPath = computed(() => {
  const a = attach.value
  const path = pick(a, ['tableName', 'filePath', 'apiUrl'], '')
  return path || pick(detail.value, ['tableName', 'filePath', 'apiUrl'], '-')
})

function statusText(v) {
  return STATUS_TEXT[String(v || '').toUpperCase()] || v || '-'
}

function shareTypeText(v) {
  const s = String(v || '').toUpperCase()
  if (['UNCONDITIONAL', 'SHARE', 'OPEN', 'FREE'].includes(s)) return '无条件共享'
  if (['CONDITIONAL', 'CONDITION'].includes(s)) return '有条件共享'
  if (['UNSHARED', 'NO_SHARE', 'NOT_SHARE', 'FORBIDDEN', 'NONE'].includes(s)) return '不予共享'
  return v || '-'
}

function shareTypeTag(v) {
  const s = String(v || '').toUpperCase()
  if (['UNCONDITIONAL', 'SHARE', 'OPEN', 'FREE'].includes(s)) return 'success'
  if (['CONDITIONAL', 'CONDITION'].includes(s)) return 'warning'
  return 'info'
}

function levelText(v) {
  const map = { 1: '1 级 公开', 2: '2 级 内部', 3: '3 级 敏感', 4: '4 级 核心' }
  return map[Number(v)] || (v ? `${v} 级` : '-')
}

function levelTag(v) {
  const n = Number(v)
  if (n >= 4) return 'danger'
  if (n === 3) return 'warning'
  if (n === 2) return 'info'
  return 'success'
}

function isSensitive(row) {
  if (!row) return false
  if (row.sensitive !== undefined) return !!row.sensitive
  if (row.isSensitive !== undefined) return !!row.isSensitive
  if (row.maskRuleCode || row.maskType) return true
  return Number(row.level) >= 3
}

async function loadDetail() {
  loading.value = true
  try {
    const res = await request.get(`/catalog/v1/resources/${id.value}`)
    const d = res.data || {}
    detail.value = d
    items.value = listOf(d.items || d.catalogItems || d.itemList || [])
  } catch (e) {
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function loadVersions() {
  try {
    const res = await request.get(`/catalog/v1/resources/${id.value}/versions`)
    versions.value = listOf(res.data)
  } catch (e) {
    versions.value = []
  }
}

async function loadPreview() {
  try {
    const res = await request.get(`/catalog/v1/resources/${id.value}/preview`)
    const d = res.data || {}
    let cols = d.columns || d.headers || d.fields || []
    let rowList = d.rows || d.list || d.records || (Array.isArray(d) ? d : [])
    if (Array.isArray(cols) && cols.length && typeof cols[0] === 'object') {
      cols = cols.map((c) => pick(c, ['fieldName', 'name', 'label'], ''))
    }
    if (!Array.isArray(cols) || !cols.length) {
      const first = rowList[0]
      if (first && typeof first === 'object' && !Array.isArray(first)) cols = Object.keys(first)
    }
    const normalized = rowList.map((r) => {
      if (Array.isArray(r)) {
        const obj = {}
        cols.forEach((c, i) => {
          obj[c] = r[i]
        })
        return obj
      }
      return r
    })
    previewColumns.value = (cols || []).filter(Boolean)
    previewRows.value = normalized
  } catch (e) {
    previewColumns.value = []
    previewRows.value = []
  }
}

function openApply() {
  if (!canApply.value) {
    ElMessage.warning('仅已发布资源可申请共享')
    return
  }
  applyVisible.value = true
}

async function submitApply() {
  try {
    await applyFormRef.value.validate()
  } catch (e) {
    return
  }
  applying.value = true
  try {
    const today = new Date()
    const end = new Date(Date.now() + applyForm.expireDays * 86400000)
    const iso = (d) => d.toISOString().slice(0, 10)
    await request.post('/approval/v1/applies', {
      catalogId: Number(id.value) || id.value,
      purpose: applyForm.purpose,
      scope: applyForm.scope,
      startDate: iso(today),
      endDate: iso(end),
      itemScope: ''
    })
    ElMessage.success('申请已提交，可在「我的申请」查看进度')
    applyVisible.value = false
    router.push('/approval')
  } catch (e) {
    // 统一错误提示
  } finally {
    applying.value = false
  }
}

onMounted(() => {
  loadDetail()
  loadVersions()
  loadPreview()
})
</script>
