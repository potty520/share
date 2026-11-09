<template>
  <div class="page-card" v-loading="loading">
    <div class="page-header">
      <div>
        <h3 class="page-title">数据质量</h3>
        <div class="page-subtitle">配置质量规则、执行检核、查看报告与问题整改闭环</div>
      </div>
      <div class="toolbar">
        <el-button type="primary" :icon="Plus" @click="openCreateRule">新增规则</el-button>
        <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="onTab">
      <!-- 规则 -->
      <el-tab-pane label="质量规则" name="rules">
        <el-table :data="rules" stripe border size="small" empty-text="暂无质量规则">
          <el-table-column label="规则名称" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ ruleName(row) }}</template>
          </el-table-column>
          <el-table-column label="规则编码" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['ruleCode'], '-') }}</template>
          </el-table-column>
          <el-table-column label="规则类型" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ ruleTypeText(row.ruleType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="检核对象" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ objectText(row) }}</template>
          </el-table-column>
          <el-table-column label="阈值/表达式" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['expression', 'threshold'], '-') }}</template>
          </el-table-column>
          <el-table-column label="最近得分" width="100" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.lastScore !== undefined && row.lastScore !== null" size="small" :type="scoreTag(row.lastScore)">{{ row.lastScore }}</el-tag>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled !== false"
                :loading="togglingId === row.id"
                inline-prompt
                active-text="启用"
                inactive-text="停用"
                @change="(v) => toggleRule(row, v)"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="success" :loading="runningId === row.id" @click="runRule(row)">执行检核</el-button>
              <el-button link type="primary" @click="openEditRule(row)">编辑</el-button>
              <el-button link type="danger" @click="removeRule(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 报告 -->
      <el-tab-pane label="检核报告" name="reports">
        <el-table :data="reports" stripe border size="small" empty-text="暂无检核报告，请先执行规则检核">
          <el-table-column label="报告编号" width="100">
            <template #default="{ row }">{{ pick(row, ['id'], '-') }}</template>
          </el-table-column>
          <el-table-column label="规则" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['ruleName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="检核对象" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['targetTable'], '-') }}{{ row.targetField ? '.' + row.targetField : '' }}</template>
          </el-table-column>
          <el-table-column label="得分" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="scoreTag(row.score)" size="small">{{ pick(row, ['score'], '-') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="总行数" width="110" align="right">
            <template #default="{ row }">{{ fmtNumber(pick(row, ['totalRows'], '-')) }}</template>
          </el-table-column>
          <el-table-column label="问题行数" width="110" align="right">
            <template #default="{ row }">{{ fmtNumber(pick(row, ['problemRows'], '-')) }}</template>
          </el-table-column>
          <el-table-column label="结论" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.passFlag ? 'success' : 'danger'">{{ row.passFlag ? '通过' : '未通过' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="检核时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['checkTime', 'createTime'])) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openReport(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="reportPage"
            :page-size="reportSize"
            background
            layout="total, prev, pager, next"
            :total="reportTotal"
            @current-change="loadReports"
          />
        </div>
      </el-tab-pane>

      <!-- 问题工单 -->
      <el-tab-pane label="问题工单" name="issues">
        <el-table :data="issues" stripe border size="small" empty-text="暂无质量问题工单">
          <el-table-column label="问题描述" min-width="230" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['issueDesc', 'description', 'title'], '-') }}</template>
          </el-table-column>
          <el-table-column label="关联规则" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['ruleName', 'ruleCode'], '-') }}</template>
          </el-table-column>
          <el-table-column label="检核对象" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['targetTable'], '-') }}{{ row.targetField ? '.' + row.targetField : '' }}</template>
          </el-table-column>
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="issueLevelTag(row.issueLevel)">{{ issueLevelText(row.issueLevel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(row.status)">{{ issueStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发现时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['foundTime', 'createTime'])) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="success" :disabled="isClosed(row)" @click="closeIssue(row)">整改关闭</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="issuePage"
            :page-size="issueSize"
            background
            layout="total, prev, pager, next"
            :total="issueTotal"
            @current-change="loadIssues"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 规则编辑 -->
    <el-dialog v-model="ruleVisible" :title="ruleForm.id ? '编辑质量规则' : '新增质量规则'" width="640px" append-to-body destroy-on-close>
      <el-form ref="ruleFormRef" :model="ruleForm" :rules="ruleRules" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="规则名称" prop="ruleName">
              <el-input v-model="ruleForm.ruleName" placeholder="如：企业名称非空校验" maxlength="80" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="规则编码" prop="ruleCode">
              <el-input v-model="ruleForm.ruleCode" placeholder="留空自动生成" maxlength="60" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="规则类型" prop="ruleType">
              <el-select v-model="ruleForm.ruleType" style="width: 100%">
                <el-option label="完整性" value="COMPLETENESS" />
                <el-option label="唯一性" value="UNIQUENESS" />
                <el-option label="规范性" value="FORMAT" />
                <el-option label="一致性" value="CONSISTENCY" />
                <el-option label="及时性" value="TIMELINESS" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目标表" prop="targetTable">
              <el-input v-model="ruleForm.targetTable" placeholder="如：t_enterprise" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目标字段">
              <el-input v-model="ruleForm.targetField" placeholder="如：name（完整性/唯一性必填）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="阈值">
              <el-input-number v-model="ruleForm.threshold" :min="0" :precision="2" :controls="false" style="width: 100%" placeholder="数值阈值" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="表达式">
              <el-input v-model="ruleForm.expression" type="textarea" :rows="2" placeholder="FORMAT 填正则；CONSISTENCY 填布尔表达式" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="检核 cron">
              <el-input v-model="ruleForm.checkCron" placeholder="可选，如 0 0 4 * * ?" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="启用">
              <el-switch v-model="ruleForm.enabled" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="说明">
              <el-input v-model="ruleForm.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 报告详情 -->
    <el-drawer v-model="reportVisible" title="检核报告详情" size="760px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="报告编号">{{ pick(reportDetail, ['id'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="规则">{{ pick(reportDetail, ['ruleName'], '-') }}</el-descriptions-item>
        <el-descriptions-item label="得分">
          <el-tag :type="scoreTag(reportDetail.score)">{{ pick(reportDetail, ['score'], '-') }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="结论">
          <el-tag :type="reportDetail.passFlag ? 'success' : 'danger'">{{ reportDetail.passFlag ? '通过' : '未通过' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="总行数">{{ fmtNumber(pick(reportDetail, ['totalRows'], '-')) }}</el-descriptions-item>
        <el-descriptions-item label="问题行数">{{ fmtNumber(pick(reportDetail, ['problemRows'], '-')) }}</el-descriptions-item>
        <el-descriptions-item label="检核对象">{{ pick(reportDetail, ['targetTable'], '-') }}{{ reportDetail.targetField ? '.' + reportDetail.targetField : '' }}</el-descriptions-item>
        <el-descriptions-item label="检核时间">{{ fmtDateTime(pick(reportDetail, ['checkTime', 'createTime'])) }}</el-descriptions-item>
      </el-descriptions>
      <h4 class="section-title mt-12">问题明细（工单）</h4>
      <el-table :data="reportIssues" size="small" border stripe empty-text="本次检核未发现问题">
        <el-table-column label="#" type="index" width="50" />
        <el-table-column label="问题描述" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['issueDesc', 'description', 'title'], '-') }}</template>
        </el-table-column>
        <el-table-column label="样例数据" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['sampleData'], '-') }}</template>
        </el-table-column>
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="issueLevelTag(row.issueLevel)">{{ issueLevelText(row.issueLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ issueStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <h4 class="section-title mt-12">问题样例</h4>
      <pre v-if="reportSamples.length" class="code-block">{{ JSON.stringify(reportSamples, null, 2) }}</pre>
      <div v-else class="muted">无样例数据</div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import request from '@/api/request'
import { confirmBox, fmtDateTime, fmtNumber, listOf, pick, tagType } from '@/api/helpers'

const loading = ref(false)
const tab = ref('rules')
const rules = ref([])
const reports = ref([])
const reportPage = ref(1)
const reportSize = 10
const reportTotal = ref(0)
const issues = ref([])
const issuePage = ref(1)
const issueSize = 10
const issueTotal = ref(0)
const runningId = ref(null)
const togglingId = ref(null)

const ruleVisible = ref(false)
const saving = ref(false)
const ruleFormRef = ref()
const ruleForm = reactive(emptyRule())
const ruleRules = {
  ruleName: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  ruleCode: [{ required: true, message: '请输入规则编码', trigger: 'blur' }],
  ruleType: [{ required: true, message: '请选择规则类型', trigger: 'change' }],
  targetTable: [{ required: true, message: '请输入检核目标表', trigger: 'blur' }]
}

const reportVisible = ref(false)
const reportDetail = ref({})
const reportIssues = ref([])
const reportSamples = ref([])

const RULE_TYPE = {
  COMPLETENESS: '完整性',
  UNIQUENESS: '唯一性',
  FORMAT: '规范性',
  VALIDITY: '规范性',
  CONSISTENCY: '一致性',
  TIMELINESS: '及时性'
}
const ISSUE_STATUS = { OPEN: '待整改', PENDING: '待整改', CLOSED: '已关闭', FIXED: '已整改', IGNORED: '已忽略' }
const LEVEL_TEXT = { P0: '严重', P1: '高', P2: '中', P3: '低', HIGH: '高', MEDIUM: '中', LOW: '低' }

function emptyRule() {
  return {
    id: null,
    ruleName: '',
    ruleCode: '',
    ruleType: 'COMPLETENESS',
    targetTable: '',
    targetField: '',
    expression: '',
    threshold: null,
    enabled: true,
    checkCron: '',
    description: ''
  }
}

function ruleName(row) {
  return pick(row, ['ruleName', 'name'], `规则 #${row?.id ?? ''}`)
}

function ruleTypeText(v) {
  return RULE_TYPE[String(v || '').toUpperCase()] || v || '-'
}

function objectText(row) {
  const table = pick(row, ['targetTable'], '')
  const field = pick(row, ['targetField'], '')
  if (table && field) return `${table}.${field}`
  return table || '-'
}

function scoreTag(score) {
  const n = Number(score)
  if (Number.isNaN(n)) return 'info'
  if (n >= 90) return 'success'
  if (n >= 70) return 'warning'
  return 'danger'
}

function issueStatusText(v) {
  return ISSUE_STATUS[String(v || '').toUpperCase()] || v || '-'
}

function issueLevelText(v) {
  return LEVEL_TEXT[String(v ?? '').toUpperCase()] || v || '-'
}

function issueLevelTag(v) {
  const s = String(v ?? '').toUpperCase()
  if (['P0', 'HIGH', '1'].includes(s)) return 'danger'
  if (['P1', 'MEDIUM', '2'].includes(s)) return 'warning'
  return 'info'
}

function isClosed(row) {
  return ['CLOSED', 'FIXED', 'DONE', 'IGNORED'].includes(String(row.status || '').toUpperCase())
}

async function loadRules() {
  loading.value = true
  try {
    const res = await request.get('/quality/v1/rules', { params: { pageNum: 1, pageSize: 100 } })
    rules.value = listOf(res.data)
  } catch (e) {
    rules.value = []
  } finally {
    loading.value = false
  }
}

async function loadReports() {
  try {
    const res = await request.get('/quality/v1/reports', { params: { pageNum: reportPage.value, pageSize: reportSize } })
    reports.value = listOf(res.data)
    reportTotal.value = res.page?.total ?? reports.value.length
  } catch (e) {
    reports.value = []
    reportTotal.value = 0
  }
}

async function loadIssues() {
  try {
    const res = await request.get('/quality/v1/issues', { params: { pageNum: issuePage.value, pageSize: issueSize } })
    issues.value = listOf(res.data)
    issueTotal.value = res.page?.total ?? issues.value.length
  } catch (e) {
    issues.value = []
    issueTotal.value = 0
  }
}

function reloadAll() {
  loadRules()
  loadReports()
  loadIssues()
}

function onTab(name) {
  if (name === 'reports') loadReports()
  if (name === 'issues') loadIssues()
}

function openCreateRule() {
  Object.assign(ruleForm, emptyRule())
  ruleVisible.value = true
}

function openEditRule(row) {
  Object.assign(ruleForm, emptyRule())
  ruleForm.id = row.id
  ruleForm.ruleName = ruleName(row)
  ruleForm.ruleCode = pick(row, ['ruleCode'], '')
  ruleForm.ruleType = pick(row, ['ruleType'], 'COMPLETENESS')
  ruleForm.targetTable = pick(row, ['targetTable'], '')
  ruleForm.targetField = pick(row, ['targetField'], '')
  ruleForm.expression = pick(row, ['expression'], '')
  ruleForm.threshold = row.threshold === undefined || row.threshold === null ? null : Number(row.threshold)
  ruleForm.enabled = row.enabled !== false
  ruleForm.checkCron = pick(row, ['checkCron'], '')
  ruleForm.description = pick(row, ['description'], '')
  ruleVisible.value = true
}

async function saveRule() {
  try {
    await ruleFormRef.value.validate()
  } catch (e) {
    return
  }
  if (!ruleForm.ruleCode) ruleForm.ruleCode = `QR-${Date.now()}`
  saving.value = true
  try {
    const payload = {
      ruleCode: ruleForm.ruleCode,
      ruleName: ruleForm.ruleName,
      ruleType: ruleForm.ruleType,
      targetTable: ruleForm.targetTable,
      targetField: ruleForm.targetField || undefined,
      expression: ruleForm.expression || undefined,
      threshold: ruleForm.threshold === null || ruleForm.threshold === undefined ? undefined : Number(ruleForm.threshold),
      enabled: ruleForm.enabled,
      checkCron: ruleForm.checkCron || undefined,
      description: ruleForm.description
    }
    if (ruleForm.id) await request.put(`/quality/v1/rules/${ruleForm.id}`, payload)
    else await request.post('/quality/v1/rules', payload)
    ElMessage.success('规则已保存')
    ruleVisible.value = false
    loadRules()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function runRule(row) {
  runningId.value = row.id
  try {
    const res = await request.post(`/quality/v1/rules/${row.id}/run`)
    const d = res.data || {}
    ElMessage.success(`检核完成${d.score !== undefined ? `，得分 ${d.score}` : ''}`)
    loadReports()
    loadIssues()
    loadRules()
  } catch (e) {
    // 拦截器已提示
  } finally {
    runningId.value = null
  }
}

async function toggleRule(row, enabled) {
  togglingId.value = row.id
  try {
    await request.put(`/quality/v1/rules/${row.id}`, {
      ruleCode: pick(row, ['ruleCode'], ''),
      ruleName: ruleName(row),
      ruleType: pick(row, ['ruleType'], ''),
      targetTable: pick(row, ['targetTable'], ''),
      targetField: pick(row, ['targetField'], '') || undefined,
      expression: pick(row, ['expression'], '') || undefined,
      threshold: row.threshold === undefined || row.threshold === null ? undefined : Number(row.threshold),
      enabled,
      checkCron: pick(row, ['checkCron'], '') || undefined,
      description: pick(row, ['description'], '')
    })
    ElMessage.success(enabled ? '规则已启用' : '规则已停用')
    loadRules()
  } catch (e) {
    // 拦截器已提示
  } finally {
    togglingId.value = null
  }
}

async function removeRule(row) {
  const ok = await confirmBox(`确认删除规则「${ruleName(row)}」？`, '删除规则', 'error')
  if (!ok) return
  try {
    await request.delete(`/quality/v1/rules/${row.id}`)
    ElMessage.success('规则已删除')
    loadRules()
  } catch (e) {
    // 拦截器已提示
  }
}

async function openReport(row) {
  reportDetail.value = row
  reportIssues.value = []
  reportSamples.value = []
  reportVisible.value = true
  try {
    const res = await request.get(`/quality/v1/reports/${pick(row, ['id'])}`)
    const d = res.data || {}
    reportDetail.value = d.report || d
    reportIssues.value = listOf(d.issues || [])
    reportSamples.value = listOf(d.samples || [])
  } catch (e) {
    // 保留列表数据即可
  }
}

async function closeIssue(row) {
  const ok = await confirmBox('确认关闭该质量问题工单？', '整改关闭')
  if (!ok) return
  try {
    await request.put(`/quality/v1/issues/${row.id}/close`, { remark: '前端整改关闭' })
    ElMessage.success('工单已关闭')
    loadIssues()
  } catch (e) {
    // 拦截器已提示
  }
}

onMounted(() => {
  loadRules()
  loadReports()
  loadIssues()
})
</script>
