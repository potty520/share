<template>
  <div v-loading="loading">
    <div class="page-card">
      <div class="page-header">
        <div>
          <h3 class="page-title">编目管理</h3>
          <div class="page-subtitle">维护数据资源目录、数据项与审核发布流程</div>
        </div>
        <div class="toolbar">
          <el-button type="primary" :icon="Plus" @click="openCreate">新增资源</el-button>
          <el-button :icon="Refresh" @click="load">刷新</el-button>
        </div>
      </div>

      <el-form inline class="filter-bar" @submit.prevent>
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" placeholder="名称 / 编码" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 140px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审核" value="PENDING" />
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="已下架" value="OFFLINE" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类">
          <el-tree-select
            v-model="query.classifyId"
            :data="classifyTree"
            clearable
            check-strictly
            :props="{ label: 'label', value: 'value', children: 'children' }"
            placeholder="全部分类"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="search">查询</el-button>
          <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" stripe border empty-text="暂无编目数据">
        <el-table-column label="资源名称" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-link" @click="$router.push(`/catalog/${row.id}`)">{{ nameOf(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="编码" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['resourceCode', 'code'], '-') }}</template>
        </el-table-column>
        <el-table-column label="分类" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['classifyName', 'classify'], '-') }}</template>
        </el-table-column>
        <el-table-column label="部门" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['deptName', 'departmentName'], '-') }}</template>
        </el-table-column>
        <el-table-column label="密级" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="levelTag(levelOf(row))">{{ levelOf(row) || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据项" width="80" align="center">
          <template #default="{ row }">{{ itemCount(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="155">
          <template #default="{ row }">{{ fmtDateTime(pick(row, ['updateTime', 'updatedAt', 'createTime'])) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="isDraft(row)" link type="warning" @click="submitAudit(row)">提交审核</el-button>
            <el-button v-if="isPending(row)" link type="success" @click="openAudit(row)">审核</el-button>
            <el-button v-if="isPublished(row)" link type="danger" @click="offline(row)">下架</el-button>
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
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑资源' : '新增资源'" width="960px" top="5vh" append-to-body destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="96px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="资源名称" prop="resourceName">
              <el-input v-model="form.resourceName" placeholder="如：企业基础信息" maxlength="80" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="资源编码" prop="resourceCode">
              <el-input v-model="form.resourceCode" placeholder="留空自动生成，如 CAT-ENTERPRISE-001" maxlength="60" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分类">
              <el-tree-select
                v-model="form.classifyId"
                :data="classifyTree"
                clearable
                check-strictly
                :props="{ label: 'label', value: 'value', children: 'children' }"
                placeholder="请选择分类"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="提供部门">
              <el-select v-model="form.deptId" clearable filterable placeholder="请选择部门" style="width: 100%">
                <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="共享属性" prop="shareType">
              <el-select v-model="form.shareType" style="width: 100%">
                <el-option label="无条件共享" value="UNCONDITIONAL" />
                <el-option label="有条件共享" value="CONDITIONAL" />
                <el-option label="不予共享" value="NO_SHARE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="密级" prop="secretLevel">
              <el-select v-model="form.secretLevel" style="width: 100%">
                <el-option label="1 级 公开" :value="1" />
                <el-option label="2 级 内部" :value="2" />
                <el-option label="3 级 敏感" :value="3" />
                <el-option label="4 级 核心" :value="4" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="挂接数据源">
              <el-select v-model="form.sourceId" clearable filterable placeholder="选择数据源" style="width: 100%">
                <el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="挂接方式">
              <el-select v-model="form.sourceType" style="width: 100%">
                <el-option label="库表" value="TABLE" />
                <el-option label="文件" value="FILE" />
                <el-option label="接口" value="API" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="form.sourceType === 'FILE' ? '文件路径' : form.sourceType === 'API' ? '接口地址' : '数据表名'">
              <el-input
                v-model="form.attachValue"
                :placeholder="form.sourceType === 'FILE' ? '/data/share/enterprise.csv' : form.sourceType === 'API' ? '/api/open/v1/xxx' : 'demo_enterprise'"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="更新周期">
              <el-select v-model="form.updateFrequency" clearable placeholder="选择更新周期" style="width: 100%">
                <el-option label="实时" value="REALTIME" />
                <el-option label="每日" value="DAILY" />
                <el-option label="每周" value="WEEKLY" />
                <el-option label="每月" value="MONTHLY" />
                <el-option label="不定期" value="IRREGULAR" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="提供方联系">
              <el-input v-model="form.providerContact" placeholder="联系人 / 联系电话" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="标签">
              <el-input v-model="form.tags" placeholder="多个标签用逗号分隔" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="资源摘要">
              <el-input v-model="form.summary" type="textarea" :rows="2" maxlength="500" show-word-limit />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="form.remark" type="textarea" :rows="1" maxlength="200" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">数据项定义</el-divider>
        <el-table :data="form.items" size="small" border>
          <el-table-column label="字段名" width="150">
            <template #default="{ row }">
              <el-input v-model="row.fieldName" size="small" placeholder="field_name" />
            </template>
          </el-table-column>
          <el-table-column label="中文名称" width="140">
            <template #default="{ row }">
              <el-input v-model="row.fieldComment" size="small" placeholder="中文名" />
            </template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-select v-model="row.fieldType" size="small" placeholder="类型">
                <el-option label="字符型" value="STRING" />
                <el-option label="数值型" value="NUMBER" />
                <el-option label="日期型" value="DATE" />
                <el-option label="布尔型" value="BOOLEAN" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="排序" width="80">
            <template #default="{ row }">
              <el-input-number v-model="row.sortNo" :min="0" :controls="false" size="small" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="敏感" width="70" align="center">
            <template #default="{ row }">
              <el-checkbox v-model="row.sensitive" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="脱敏规则编码" width="140">
            <template #default="{ row }">
              <el-input v-model="row.maskRuleCode" size="small" placeholder="如 MASK_PHONE" />
            </template>
          </el-table-column>
          <el-table-column label="样例值" min-width="120">
            <template #default="{ row }">
              <el-input v-model="row.sampleValue" size="small" placeholder="样例值" />
            </template>
          </el-table-column>
          <el-table-column label="" width="60" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" :icon="Delete" @click="form.items.splice($index, 1)" />
            </template>
          </el-table-column>
        </el-table>
        <el-button class="mt-8" size="small" :icon="Plus" @click="addItem">添加数据项</el-button>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save(false)">保存草稿</el-button>
        <el-button type="success" :loading="saving" @click="save(true)">保存并提交审核</el-button>
      </template>
    </el-dialog>

    <!-- 审核 -->
    <el-dialog v-model="auditVisible" title="资源审核" width="520px" append-to-body>
      <el-form label-width="84px">
        <el-form-item label="资源名称">
          <span>{{ auditRow ? nameOf(auditRow) : '' }}</span>
        </el-form-item>
        <el-form-item label="审核结果">
          <el-radio-group v-model="auditForm.approved">
            <el-radio :value="true">通过并发布</el-radio>
            <el-radio :value="false">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="审核意见">
          <el-input v-model="auditForm.comment" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="请填写审核意见" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditVisible = false">取消</el-button>
        <el-button type="primary" :loading="auditing" @click="submitAuditResult">提交审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Delete, Search, Refresh, RefreshLeft } from '@element-plus/icons-vue'
import request from '@/api/request'
import {
  clean,
  confirmBox,
  fmtDateTime,
  listOf,
  pageOf,
  pick,
  tagType
} from '@/api/helpers'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const query = reactive({ keyword: '', status: '', classifyId: null })
const classifyTree = ref([])
const depts = ref([])
const sources = ref([])

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive(emptyForm())
const formRules = {
  resourceName: [{ required: true, message: '请输入资源名称', trigger: 'blur' }],
  shareType: [{ required: true, message: '请选择共享属性', trigger: 'change' }],
  secretLevel: [{ required: true, message: '请选择密级', trigger: 'change' }]
}

const auditVisible = ref(false)
const auditing = ref(false)
const auditRow = ref(null)
const auditForm = reactive({ approved: true, comment: '' })

const STATUS_TEXT = { DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', OFFLINE: '已下架' }

function emptyForm() {
  return {
    id: null,
    resourceName: '',
    resourceCode: '',
    classifyId: null,
    deptId: null,
    shareType: 'CONDITIONAL',
    secretLevel: 2,
    sourceId: null,
    sourceType: 'TABLE',
    attachValue: '',
    updateFrequency: 'DAILY',
    providerContact: '',
    tags: '',
    summary: '',
    remark: '',
    items: []
  }
}

function statusText(v) {
  return STATUS_TEXT[String(v || '').toUpperCase()] || v || '-'
}

function levelOf(row) {
  return pick(row, ['secretLevel', 'level'], '')
}

function levelTag(v) {
  const n = Number(v)
  if (n >= 4) return 'danger'
  if (n === 3) return 'warning'
  if (n === 2) return 'info'
  return 'success'
}

function nameOf(row) {
  return pick(row, ['resourceName', 'name', 'title'], `资源 #${row?.id ?? ''}`)
}

function isDraft(row) {
  return ['DRAFT', 'REJECTED'].includes(String(row.status || '').toUpperCase())
}

function isPending(row) {
  return String(row.status || '').toUpperCase() === 'PENDING'
}

function isPublished(row) {
  return String(row.status || '').toUpperCase() === 'PUBLISHED'
}

function itemCount(row) {
  const list = row.items || row.catalogItems
  if (Array.isArray(list)) return list.length
  return pick(row, ['itemCount', 'fieldCount'], 0)
}

function toTree(list) {
  return list.map((it) => ({
    value: it.id ?? it.value,
    label: it.name ?? it.label ?? it.title,
    children: it.children && it.children.length ? toTree(it.children) : undefined
  }))
}

function flattenDepts(list, prefix = '') {
  const out = []
  list.forEach((d) => {
    out.push({ id: d.id, name: `${prefix}${d.deptName || d.name || ''}` })
    const children = d.children || d.childList || []
    if (children.length) out.push(...flattenDepts(children, `${prefix}　`))
  })
  return out
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/catalog/v1/resources', {
      params: clean({ ...query, pageNum: pageNum.value, pageSize: pageSize.value, all: true })
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
  query.classifyId = null
  search()
}

function addItem() {
  form.items.push({ fieldName: '', fieldComment: '', fieldType: 'STRING', sortNo: form.items.length, sensitive: false, maskRuleCode: '', sampleValue: '' })
}

function openCreate() {
  Object.assign(form, emptyForm())
  form.items = []
  addItem()
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(form, emptyForm())
  form.id = row.id
  form.resourceName = nameOf(row)
  form.resourceCode = pick(row, ['resourceCode', 'code'], '')
  form.classifyId = row.classifyId ?? null
  form.deptId = row.deptId ?? null
  form.shareType = row.shareType || 'CONDITIONAL'
  form.secretLevel = Number(levelOf(row)) || 2
  form.sourceId = row.sourceId ?? null
  const attach = row.attach || {}
  form.sourceType = pick(attach, ['mode'], 'TABLE') === 'NONE' ? 'TABLE' : pick(attach, ['mode'], 'TABLE')
  form.attachValue = pick(attach, ['tableName', 'filePath', 'apiUrl'], pick(row, ['tableName', 'filePath', 'apiUrl'], ''))
  form.updateFrequency = pick(row, ['updateFrequency'], 'DAILY')
  form.providerContact = pick(row, ['providerContact'], '')
  const tags = row.tagList || row.tags
  form.tags = Array.isArray(tags) ? tags.map((t) => (typeof t === 'string' ? t : pick(t, ['name', 'tagName'], ''))).join(',') : tags || ''
  form.summary = pick(row, ['summary', 'description'], '')
  form.remark = pick(row, ['remark'], '')
  const items = row.items || []
  form.items = Array.isArray(items) && items.length
    ? items.map((it, i) => ({
        fieldName: pick(it, ['fieldName'], ''),
        fieldComment: pick(it, ['fieldComment', 'fieldLabel'], ''),
        fieldType: pick(it, ['fieldType'], 'STRING'),
        sortNo: Number(pick(it, ['sortNo'], i)) || i,
        sensitive: !!it.sensitive,
        maskRuleCode: pick(it, ['maskRuleCode'], ''),
        sampleValue: pick(it, ['sampleValue'], '')
      }))
    : []
  if (!form.items.length) addItem()
  dialogVisible.value = true
}

function payloadOf() {
  const attach = {
    tableName: form.sourceType === 'TABLE' ? form.attachValue : undefined,
    filePath: form.sourceType === 'FILE' ? form.attachValue : undefined,
    apiUrl: form.sourceType === 'API' ? form.attachValue : undefined
  }
  return {
    resourceCode: form.resourceCode || undefined,
    resourceName: form.resourceName,
    summary: form.summary,
    classifyId: form.classifyId,
    deptId: form.deptId,
    shareType: form.shareType,
    secretLevel: form.secretLevel,
    sourceId: form.sourceId,
    sourceType: form.sourceType,
    ...attach,
    updateFrequency: form.updateFrequency,
    providerContact: form.providerContact,
    tags: form.tags ? String(form.tags).split(',').map((s) => s.trim()).filter(Boolean) : [],
    remark: form.remark,
    items: form.items
      .filter((it) => it.fieldName)
      .map((it, i) => ({ ...it, sortNo: Number(it.sortNo) || i }))
  }
}

async function save(submitAfter) {
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  if (!form.items.some((it) => it.fieldName)) {
    ElMessage.warning('请至少填写一个数据项字段')
    return
  }
  saving.value = true
  try {
    let resourceId = form.id
    if (form.id) {
      await request.put(`/catalog/v1/resources/${form.id}`, payloadOf())
    } else {
      const res = await request.post('/catalog/v1/resources', payloadOf())
      resourceId = res.data?.id ?? res.data
    }
    if (submitAfter && resourceId) {
      await request.post(`/catalog/v1/resources/${resourceId}/submit`)
      ElMessage.success('已保存并提交审核')
    } else {
      ElMessage.success(form.id ? '修改已保存' : '资源已创建为草稿')
    }
    dialogVisible.value = false
    load()
  } catch (e) {
    // 统一错误提示
  } finally {
    saving.value = false
  }
}

async function submitAudit(row) {
  const ok = await confirmBox(`确认将「${nameOf(row)}」提交审核？`, '提交审核')
  if (!ok) return
  try {
    await request.post(`/catalog/v1/resources/${row.id}/submit`)
    ElMessage.success('已提交审核')
    load()
  } catch (e) {
    // 统一错误提示
  }
}

function openAudit(row) {
  auditRow.value = row
  auditForm.approved = true
  auditForm.comment = ''
  auditVisible.value = true
}

async function submitAuditResult() {
  if (!auditRow.value) return
  auditing.value = true
  try {
    await request.post(`/catalog/v1/resources/${auditRow.value.id}/audit`, {
      approved: auditForm.approved,
      comment: auditForm.comment
    })
    ElMessage.success(auditForm.approved ? '审核通过，资源已发布' : '已驳回该资源')
    auditVisible.value = false
    load()
  } catch (e) {
    // 统一错误提示
  } finally {
    auditing.value = false
  }
}

async function offline(row) {
  const ok = await confirmBox(`确认下架「${nameOf(row)}」？下架后使用方将无法检索到该资源。`, '下架资源')
  if (!ok) return
  try {
    await request.post(`/catalog/v1/resources/${row.id}/offline`)
    ElMessage.success('资源已下架')
    load()
  } catch (e) {
    // 统一错误提示
  }
}

async function remove(row) {
  const ok = await confirmBox(`确认删除「${nameOf(row)}」？删除后不可恢复。`, '删除资源', 'error')
  if (!ok) return
  try {
    await request.delete(`/catalog/v1/resources/${row.id}`)
    ElMessage.success('资源已删除')
    load()
  } catch (e) {
    // 统一错误提示
  }
}

async function loadDicts() {
  try {
    const res = await request.get('/catalog/v1/classifies')
    classifyTree.value = toTree(listOf(res.data))
  } catch (e) {
    classifyTree.value = []
  }
  try {
    const res = await request.get('/user/v1/depts')
    depts.value = flattenDepts(listOf(res.data))
  } catch (e) {
    depts.value = []
  }
  try {
    const res = await request.get('/datasource/v1/sources', { params: { pageNum: 1, pageSize: 200 } })
    sources.value = listOf(res.data).map((s) => ({ id: s.id, name: pick(s, ['sourceName', 'name'], `数据源 #${s.id}`) }))
  } catch (e) {
    sources.value = []
  }
}

onMounted(() => {
  load()
  loadDicts()
})
</script>
