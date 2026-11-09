<template>
  <div class="page-card" v-loading="loading">
    <div class="page-header">
      <div>
        <h3 class="page-title">安全管控</h3>
        <div class="page-subtitle">分类分级模板、分级标签、脱敏规则与密钥元数据管理</div>
      </div>
      <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>
    </div>

    <el-tabs v-model="tab" @tab-change="onTab">
      <!-- 概览 -->
      <el-tab-pane label="安全概览" name="overview">
        <div class="stat-grid stat-grid--4 mb-12">
          <div class="stat-card">
            <div class="stat-label">敏感资源数</div>
            <div class="stat-value">{{ fmtNumber(pick(overview, ['sensitiveCatalogCount', 'sensitiveCount'], '-')) }}</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">脱敏规则</div>
            <div class="stat-value">{{ fmtNumber(pick(overview, ['maskRuleCount'], '-')) }}</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">分级标签</div>
            <div class="stat-value">{{ fmtNumber(pick(overview, ['tagCount'], '-')) }}</div>
          </div>
          <div class="stat-card">
            <div class="stat-label">密钥数量</div>
            <div class="stat-value">{{ fmtNumber(pick(overview, ['keyCount'], '-')) }}</div>
          </div>
        </div>
        <div class="chart-grid">
          <div>
            <h4 class="section-title">分级标签分布</h4>
            <div v-if="levelDist.length" ref="levelChartRef" class="chart-box" style="height: 300px" />
            <el-empty v-else description="暂无分级分布数据" :image-size="80" />
          </div>
          <div>
            <h4 class="section-title">标签对象类型分布</h4>
            <div v-if="typeDist.length" ref="typeChartRef" class="chart-box" style="height: 300px" />
            <el-empty v-else description="暂无标签统计数据" :image-size="80" />
          </div>
        </div>
      </el-tab-pane>

      <!-- 模板 -->
      <el-tab-pane label="分类分级模板" name="templates">
        <div class="flex-between mb-12">
          <span class="muted">分级模板用于定义数据资源的 1~4 级分类标准</span>
          <el-button size="small" type="primary" :icon="Plus" @click="openTemplate()">新增模板</el-button>
        </div>
        <el-table :data="templates" size="small" border stripe empty-text="暂无分类分级模板">
          <el-table-column label="模板名称" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['templateName', 'name'], '-') }}</template>
          </el-table-column>
          <el-table-column label="模板编码" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['templateCode'], '-') }}</template>
          </el-table-column>
          <el-table-column label="级别定义" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ levelDefsText(row) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['description', 'remark'], '-') }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openTemplate(row)">编辑</el-button>
              <el-button link type="danger" :disabled="row.builtin" @click="removeTemplate(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 标签 -->
      <el-tab-pane label="分级标签" name="tags">
        <div class="flex-between mb-12">
          <span class="muted">标签可打在目录资源（CATALOG）或字段（FIELD）上</span>
          <el-button size="small" type="primary" :icon="Plus" @click="openTag">新增标签</el-button>
        </div>
        <el-table :data="tags" size="small" border stripe empty-text="暂无分级标签">
          <el-table-column label="标签对象" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['targetName', 'targetId'], '-') }}</template>
          </el-table-column>
          <el-table-column label="对象类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ targetText(pick(row, ['targetType'], '')) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="字段" width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['fieldName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="级别" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.level >= 3 ? 'warning' : 'info'">{{ pick(row, ['levelName', 'level'], '-') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="{ row }">{{ pick(row, ['tagSource'], '-') }}</template>
          </el-table-column>
          <el-table-column label="打标时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['taggedTime', 'createTime'])) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" @click="removeTag(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 脱敏规则 -->
      <el-tab-pane label="脱敏规则" name="mask">
        <div class="flex-between mb-12">
          <span class="muted">支持遮蔽、替换、哈希、泛化、扰动、加密六类算法</span>
          <div class="toolbar">
            <el-button size="small" :icon="MagicStick" @click="openMaskTest">规则试算</el-button>
            <el-button size="small" type="primary" :icon="Plus" @click="openMask()">新增规则</el-button>
          </div>
        </div>
        <el-table :data="maskRules" size="small" border stripe empty-text="暂无脱敏规则">
          <el-table-column label="规则名称" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['ruleName', 'name'], '-') }}</template>
          </el-table-column>
          <el-table-column label="规则编码" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['ruleCode'], '-') }}</template>
          </el-table-column>
          <el-table-column label="脱敏类型" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ maskTypeText(pick(row, ['maskType'], '')) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="参数" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['param'], '-') }}</template>
          </el-table-column>
          <el-table-column label="生效字段" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['targetField'], '-') }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled === false ? 'info' : 'success'">{{ row.enabled === false ? '停用' : '启用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openMask(row)">编辑</el-button>
              <el-button link type="danger" @click="removeMask(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 密钥 -->
      <el-tab-pane label="密钥管理" name="keys">
        <div class="flex-between mb-12">
          <span class="muted">仅保存密钥 alias 与状态，明文由密钥库托管</span>
          <el-button size="small" type="primary" :icon="Plus" @click="openKey">登记密钥</el-button>
        </div>
        <el-table :data="keys" size="small" border stripe empty-text="暂无密钥元数据">
          <el-table-column label="密钥 Alias" min-width="170">
            <template #default="{ row }">{{ pick(row, ['keyAlias', 'alias'], '-') }}</template>
          </el-table-column>
          <el-table-column label="算法" width="120">
            <template #default="{ row }">{{ pick(row, ['algorithm'], '-') }}</template>
          </el-table-column>
          <el-table-column label="版本" width="80" align="center">
            <template #default="{ row }">{{ pick(row, ['keyVersion'], '-') }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(row.keyStatus || row.status)">{{ pick(row, ['keyStatus'], 'ACTIVE') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近轮换" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['lastRotatedTime', 'rotateTime'])) }}</template>
          </el-table-column>
          <el-table-column label="下次轮换" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['nextRotateTime'])) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="warning" :loading="rotatingId === row.id" @click="rotateKey(row)">轮换</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 模板编辑 -->
    <el-dialog v-model="templateVisible" :title="templateForm.id ? '编辑模板' : '新增模板'" width="560px" append-to-body>
      <el-form ref="templateRef" :model="templateForm" :rules="templateRules" label-width="96px">
        <el-form-item label="模板名称" prop="templateName">
          <el-input v-model="templateForm.templateName" placeholder="如：政务数据分类分级模板" />
        </el-form-item>
        <el-form-item label="模板编码" prop="templateCode">
          <el-input v-model="templateForm.templateCode" placeholder="如：TPL-DEFAULT" :disabled="!!templateForm.id" />
        </el-form-item>
        <el-form-item label="级别定义">
          <el-input
            v-model="templateForm.levelDefs"
            type="textarea"
            :rows="4"
            placeholder='JSON，如 [{"level":1,"name":"公开"},{"level":2,"name":"内部"},{"level":3,"name":"敏感"},{"level":4,"name":"核心"}]'
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="templateForm.enabled" inline-prompt active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="templateForm.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="templateVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveTemplate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 标签编辑 -->
    <el-dialog v-model="tagVisible" title="新增分级标签" width="520px" append-to-body>
      <el-form ref="tagRef" :model="tagForm" :rules="tagRules" label-width="96px">
        <el-form-item label="对象类型">
          <el-radio-group v-model="tagForm.targetType">
            <el-radio value="CATALOG">目录资源</el-radio>
            <el-radio value="FIELD">字段</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="对象ID" prop="targetId">
          <el-input-number v-model="tagForm.targetId" :min="1" :controls="false" style="width: 100%" placeholder="目录/字段所属资源 ID" />
        </el-form-item>
        <el-form-item label="对象名称">
          <el-input v-model="tagForm.targetName" placeholder="可选，便于展示" />
        </el-form-item>
        <el-form-item v-if="tagForm.targetType === 'FIELD'" label="字段名">
          <el-input v-model="tagForm.fieldName" placeholder="如 phone" />
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="tagForm.level" style="width: 100%" @change="onLevelChange">
            <el-option label="1 级 公开" :value="1" />
            <el-option label="2 级 内部" :value="2" />
            <el-option label="3 级 敏感" :value="3" />
            <el-option label="4 级 核心" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="tagForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tagVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveTag">保存</el-button>
      </template>
    </el-dialog>

    <!-- 脱敏规则编辑 -->
    <el-dialog v-model="maskVisible" :title="maskForm.id ? '编辑脱敏规则' : '新增脱敏规则'" width="600px" append-to-body>
      <el-form ref="maskRef" :model="maskForm" :rules="maskRulesForm" label-width="96px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="规则名称" prop="ruleName">
              <el-input v-model="maskForm.ruleName" placeholder="如：手机号遮蔽" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="规则编码" prop="ruleCode">
              <el-input v-model="maskForm.ruleCode" placeholder="如：MASK_PHONE" :disabled="!!maskForm.id" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="脱敏类型" prop="maskType">
              <el-select v-model="maskForm.maskType" style="width: 100%">
                <el-option label="遮蔽（保留首尾）" value="MASK" />
                <el-option label="替换" value="REPLACE" />
                <el-option label="哈希" value="HASH" />
                <el-option label="泛化" value="GENERALIZE" />
                <el-option label="扰动" value="PERTURB" />
                <el-option label="加密" value="ENCRYPT" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最低密级">
              <el-select v-model="maskForm.levelMin" style="width: 100%">
                <el-option label="1 级及以上" :value="1" />
                <el-option label="2 级及以上" :value="2" />
                <el-option label="3 级及以上" :value="3" />
                <el-option label="4 级" :value="4" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="算法参数">
              <el-input v-model="maskForm.param" placeholder='如 {"keepStart":3,"keepEnd":4,"char":"*"} 或 MASK 的 "3,4"' />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="生效字段">
              <el-input v-model="maskForm.targetField" placeholder="字段名或字段匹配模式，如 phone" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="匹配模式">
              <el-input v-model="maskForm.matchPattern" placeholder="可选正则，按字段值匹配" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="说明">
              <el-input v-model="maskForm.description" type="textarea" :rows="2" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="maskVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMask">保存</el-button>
      </template>
    </el-dialog>

    <!-- 规则试算 -->
    <el-dialog v-model="maskTestVisible" title="脱敏规则试算" width="560px" append-to-body>
      <el-form label-width="96px">
        <el-form-item label="脱敏类型">
          <el-select v-model="maskTest.maskType" style="width: 100%">
            <el-option label="遮蔽" value="MASK" />
            <el-option label="替换" value="REPLACE" />
            <el-option label="哈希" value="HASH" />
            <el-option label="泛化" value="GENERALIZE" />
            <el-option label="扰动" value="PERTURB" />
            <el-option label="加密" value="ENCRYPT" />
          </el-select>
        </el-form-item>
        <el-form-item label="算法参数">
          <el-input v-model="maskTest.param" placeholder='如 "3,4" 或 {"keepStart":3}' />
        </el-form-item>
        <el-form-item label="原始值">
          <el-input v-model="maskTest.value" placeholder="输入待脱敏的值，如：13800001234" />
        </el-form-item>
        <el-form-item label="脱敏结果">
          <el-input :model-value="maskTestResult" readonly placeholder="点击试算后显示" />
        </el-form-item>
        <el-form-item label="执行耗时">
          <span class="muted">{{ maskTestCost }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="maskTestVisible = false">关闭</el-button>
        <el-button type="primary" :loading="maskTesting" @click="doMaskTest">试算</el-button>
      </template>
    </el-dialog>

    <!-- 密钥登记 -->
    <el-dialog v-model="keyVisible" title="登记密钥元数据" width="500px" append-to-body>
      <el-form label-width="96px">
        <el-form-item label="密钥 Alias">
          <el-input v-model="keyForm.keyAlias" placeholder="如：mask-key-001" />
        </el-form-item>
        <el-form-item label="算法">
          <el-select v-model="keyForm.algorithm" style="width: 100%">
            <el-option label="AES-256" value="AES-256" />
            <el-option label="SM4" value="SM4" />
            <el-option label="RSA-2048" value="RSA-2048" />
          </el-select>
        </el-form-item>
        <el-form-item label="轮换周期(天)">
          <el-input-number v-model="keyForm.rotateDays" :min="1" :max="3650" style="width: 100%" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="keyForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="keyVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveKey">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, MagicStick } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import request from '@/api/request'
import { autoResize, confirmBox, fmtDateTime, fmtNumber, listOf, numOf, pick, tagType } from '@/api/helpers'

const loading = ref(false)
const tab = ref('overview')
const overview = ref({})
const templates = ref([])
const tags = ref([])
const maskRules = ref([])
const keys = ref([])
const saving = ref(false)
const rotatingId = ref(null)

const levelChartRef = ref()
const typeChartRef = ref()
let levelChart = null
let typeChart = null
let disposeResize = () => {}
const levelDist = ref([])
const typeDist = ref([])

const templateVisible = ref(false)
const templateRef = ref()
const templateForm = reactive({ id: null, templateName: '', templateCode: '', levelDefs: '', enabled: true, description: '' })
const templateRules = {
  templateName: [{ required: true, message: '请输入模板名称', trigger: 'blur' }],
  templateCode: [{ required: true, message: '请输入模板编码', trigger: 'blur' }]
}

const tagVisible = ref(false)
const tagRef = ref()
const tagForm = reactive({ targetType: 'CATALOG', targetId: null, targetName: '', fieldName: '', level: 3, levelName: '', remark: '' })
const tagRules = {
  targetId: [{ required: true, message: '请输入对象ID', trigger: 'blur' }]
}

const maskVisible = ref(false)
const maskRef = ref()
const maskForm = reactive({ id: null, ruleName: '', ruleCode: '', maskType: 'MASK', param: '', targetField: '', matchPattern: '', levelMin: 1, enabled: true, description: '' })
const maskRulesForm = {
  ruleName: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  ruleCode: [{ required: true, message: '请输入规则编码', trigger: 'blur' }],
  maskType: [{ required: true, message: '请选择脱敏类型', trigger: 'change' }]
}

const maskTestVisible = ref(false)
const maskTesting = ref(false)
const maskTest = reactive({ maskType: 'MASK', param: '3,4', value: '' })
const maskTestResult = ref('')
const maskTestCost = ref('-')

const keyVisible = ref(false)
const keyForm = reactive({ keyAlias: '', algorithm: 'AES-256', rotateDays: 90, description: '' })

const MASK_TYPE = { MASK: '遮蔽', REPLACE: '替换', HASH: '哈希', GENERALIZE: '泛化', PERTURB: '扰动', ENCRYPT: '加密' }
const LEVEL_NAMES = { 1: '1 级 公开', 2: '2 级 内部', 3: '3 级 敏感', 4: '4 级 核心' }

function maskTypeText(v) {
  return MASK_TYPE[String(v || '').toUpperCase()] || v || '-'
}

function targetText(v) {
  const s = String(v || '').toUpperCase()
  if (s === 'CATALOG') return '目录资源'
  if (s === 'FIELD') return '字段'
  return v || '-'
}

function levelDefsText(row) {
  const defs = row.levelDefs
  if (!defs) return '-'
  if (typeof defs === 'string') return defs.length > 90 ? `${defs.slice(0, 90)}…` : defs
  try {
    return JSON.stringify(defs)
  } catch (e) {
    return String(defs)
  }
}

function onLevelChange(level) {
  tagForm.levelName = LEVEL_NAMES[level] || ''
}

async function loadOverview() {
  try {
    const res = await request.get('/security/v1/overview')
    overview.value = res.data || {}
    const dist = pick(overview.value, ['levelDistribution'], [])
    levelDist.value = listOf(dist).map((d) => ({
      name: pick(d, ['levelName'], `L${pick(d, ['level'], '-')}`),
      value: numOf(pick(d, ['count'], 0))
    }))
    const targetDist = pick(overview.value, ['targetTypeDistribution'], [])
    typeDist.value = listOf(targetDist).map((d) => ({
      name: targetText(pick(d, ['targetType'], '-')),
      value: numOf(pick(d, ['count'], 0))
    }))
  } catch (e) {
    overview.value = {}
    levelDist.value = []
    typeDist.value = []
  }
}

function renderCharts() {
  if (levelDist.value.length && levelChartRef.value) {
    if (!levelChart) levelChart = echarts.init(levelChartRef.value)
    levelChart.setOption({
      color: ['#4a5ee5', '#6f86ff', '#e6a23c', '#f56c6c'],
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [
        {
          name: '分级分布',
          type: 'pie',
          radius: ['42%', '66%'],
          center: ['50%', '44%'],
          label: { formatter: '{b}: {c}' },
          data: levelDist.value
        }
      ]
    })
  }
  if (typeDist.value.length && typeChartRef.value) {
    if (!typeChart) typeChart = echarts.init(typeChartRef.value)
    typeChart.setOption({
      color: ['#67c23a', '#409eff'],
      tooltip: { trigger: 'axis' },
      grid: { left: 90, right: 24, top: 20, bottom: 22 },
      xAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f7' } } },
      yAxis: { type: 'category', data: typeDist.value.map((d) => d.name) },
      series: [{ name: '标签数', type: 'bar', barMaxWidth: 22, data: typeDist.value.map((d) => d.value) }]
    })
  }
}

async function loadTemplates() {
  try {
    const res = await request.get('/security/v1/templates', { params: { pageNum: 1, pageSize: 100 } })
    templates.value = listOf(res.data)
  } catch (e) {
    templates.value = []
  }
}

async function loadTags() {
  try {
    const res = await request.get('/security/v1/tags', { params: { pageNum: 1, pageSize: 100 } })
    tags.value = listOf(res.data)
  } catch (e) {
    tags.value = []
  }
}

async function loadMaskRules() {
  try {
    const res = await request.get('/security/v1/mask-rules', { params: { pageNum: 1, pageSize: 100 } })
    maskRules.value = listOf(res.data)
  } catch (e) {
    maskRules.value = []
  }
}

async function loadKeys() {
  try {
    const res = await request.get('/security/v1/keys')
    keys.value = listOf(res.data)
  } catch (e) {
    keys.value = []
  }
}

async function reloadAll() {
  loading.value = true
  await Promise.allSettled([loadOverview(), loadTemplates(), loadTags(), loadMaskRules(), loadKeys()])
  loading.value = false
  await nextTick()
  renderCharts()
  const cleanups = [levelChart, typeChart].filter(Boolean).map((c) => autoResize(c))
  disposeResize = () => cleanups.forEach((fn) => fn())
}

function onTab(name) {
  if (name === 'templates') loadTemplates()
  if (name === 'tags') loadTags()
  if (name === 'mask') loadMaskRules()
  if (name === 'keys') loadKeys()
  if (name === 'overview') {
    loadOverview().then(() => nextTick().then(renderCharts))
  }
}

function openTemplate(row) {
  if (row) {
    const defs = row.levelDefs
    Object.assign(templateForm, {
      id: row.id,
      templateName: pick(row, ['templateName', 'name'], ''),
      templateCode: pick(row, ['templateCode'], ''),
      levelDefs: defs == null ? '' : typeof defs === 'string' ? defs : JSON.stringify(defs, null, 2),
      enabled: row.status !== 0,
      description: pick(row, ['description', 'remark'], '')
    })
  } else {
    Object.assign(templateForm, {
      id: null,
      templateName: '',
      templateCode: '',
      levelDefs: JSON.stringify(
        [
          { level: 1, name: '公开' },
          { level: 2, name: '内部' },
          { level: 3, name: '敏感' },
          { level: 4, name: '核心' }
        ],
        null,
        2
      ),
      enabled: true,
      description: ''
    })
  }
  templateVisible.value = true
}

async function saveTemplate() {
  try {
    await templateRef.value.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    let levelDefs = templateForm.levelDefs
    if (levelDefs) {
      try {
        levelDefs = JSON.parse(levelDefs)
      } catch (e) {
        ElMessage.warning('级别定义不是合法 JSON，请检查')
        saving.value = false
        return
      }
    }
    const payload = {
      templateCode: templateForm.templateCode,
      templateName: templateForm.templateName,
      description: templateForm.description,
      levelDefs,
      status: templateForm.enabled ? 1 : 0
    }
    if (templateForm.id) await request.put(`/security/v1/templates/${templateForm.id}`, payload)
    else await request.post('/security/v1/templates', payload)
    ElMessage.success('模板已保存')
    templateVisible.value = false
    loadTemplates()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function removeTemplate(row) {
  const ok = await confirmBox('确认删除该分级模板？', '删除模板', 'error')
  if (!ok) return
  try {
    await request.delete(`/security/v1/templates/${row.id}`)
    ElMessage.success('模板已删除')
    loadTemplates()
  } catch (e) {
    // 拦截器已提示
  }
}

function openTag() {
  Object.assign(tagForm, { targetType: 'CATALOG', targetId: null, targetName: '', fieldName: '', level: 3, levelName: LEVEL_NAMES[3], remark: '' })
  tagVisible.value = true
}

async function saveTag() {
  try {
    await tagRef.value.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    await request.post('/security/v1/tags', {
      targetType: tagForm.targetType,
      targetId: tagForm.targetId,
      targetName: tagForm.targetName,
      fieldName: tagForm.fieldName || undefined,
      level: tagForm.level,
      levelName: tagForm.levelName,
      remark: tagForm.remark
    })
    ElMessage.success('标签已创建')
    tagVisible.value = false
    loadTags()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function removeTag(row) {
  const ok = await confirmBox('确认删除该分级标签？', '删除标签', 'error')
  if (!ok) return
  try {
    await request.delete(`/security/v1/tags/${row.id}`)
    ElMessage.success('标签已删除')
    loadTags()
  } catch (e) {
    // 拦截器已提示
  }
}

function openMask(row) {
  if (row) {
    Object.assign(maskForm, {
      id: row.id,
      ruleName: pick(row, ['ruleName', 'name'], ''),
      ruleCode: pick(row, ['ruleCode'], ''),
      maskType: pick(row, ['maskType'], 'MASK'),
      param: pick(row, ['param'], ''),
      targetField: pick(row, ['targetField'], ''),
      matchPattern: pick(row, ['matchPattern'], ''),
      levelMin: Number(pick(row, ['levelMin'], 1)) || 1,
      enabled: row.enabled !== false,
      description: pick(row, ['description'], '')
    })
  } else {
    Object.assign(maskForm, { id: null, ruleName: '', ruleCode: '', maskType: 'MASK', param: '', targetField: '', matchPattern: '', levelMin: 1, enabled: true, description: '' })
  }
  maskVisible.value = true
}

async function saveMask() {
  try {
    await maskRef.value.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    const payload = {
      ruleCode: maskForm.ruleCode,
      ruleName: maskForm.ruleName,
      maskType: maskForm.maskType,
      param: maskForm.param,
      targetField: maskForm.targetField,
      matchPattern: maskForm.matchPattern || undefined,
      levelMin: maskForm.levelMin,
      enabled: maskForm.enabled,
      description: maskForm.description
    }
    if (maskForm.id) await request.put(`/security/v1/mask-rules/${maskForm.id}`, payload)
    else await request.post('/security/v1/mask-rules', payload)
    ElMessage.success('脱敏规则已保存')
    maskVisible.value = false
    loadMaskRules()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function removeMask(row) {
  const ok = await confirmBox('确认删除该脱敏规则？', '删除规则', 'error')
  if (!ok) return
  try {
    await request.delete(`/security/v1/mask-rules/${row.id}`)
    ElMessage.success('规则已删除')
    loadMaskRules()
  } catch (e) {
    // 拦截器已提示
  }
}

function openMaskTest() {
  maskTest.maskType = 'MASK'
  maskTest.param = '3,4'
  maskTest.value = ''
  maskTestResult.value = ''
  maskTestCost.value = '-'
  maskTestVisible.value = true
}

async function doMaskTest() {
  if (!maskTest.value) {
    ElMessage.warning('请输入待脱敏的原始值')
    return
  }
  maskTesting.value = true
  try {
    const res = await request.post('/security/v1/mask-rules/test', {
      maskType: maskTest.maskType,
      param: maskTest.param,
      value: maskTest.value
    })
    const d = res.data || {}
    maskTestResult.value = pick(d, ['masked', 'result', 'value'], '-')
    maskTestCost.value = d.costMs !== undefined ? `${d.costMs} ms` : '-'
  } catch (e) {
    // 拦截器已提示
  } finally {
    maskTesting.value = false
  }
}

function openKey() {
  Object.assign(keyForm, { keyAlias: '', algorithm: 'AES-256', rotateDays: 90, description: '' })
  keyVisible.value = true
}

async function saveKey() {
  if (!keyForm.keyAlias) {
    ElMessage.warning('请输入密钥 Alias')
    return
  }
  saving.value = true
  try {
    await request.post('/security/v1/keys', {
      keyAlias: keyForm.keyAlias,
      algorithm: keyForm.algorithm,
      description: keyForm.description,
      rotateDays: keyForm.rotateDays
    })
    ElMessage.success('密钥元数据已登记')
    keyVisible.value = false
    loadKeys()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function rotateKey(row) {
  const ok = await confirmBox('确认轮换该密钥？轮换后旧版本密钥进入过渡期。', '密钥轮换')
  if (!ok) return
  rotatingId.value = row.id
  try {
    await request.post(`/security/v1/keys/${row.id}/rotate`)
    ElMessage.success('密钥已轮换')
    loadKeys()
  } catch (e) {
    // 拦截器已提示
  } finally {
    rotatingId.value = null
  }
}

onMounted(reloadAll)

onBeforeUnmount(() => {
  disposeResize()
  if (levelChart) {
    levelChart.dispose()
    levelChart = null
  }
  if (typeChart) {
    typeChart.dispose()
    typeChart = null
  }
})
</script>
