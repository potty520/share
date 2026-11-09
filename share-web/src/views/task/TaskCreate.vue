<template>
  <div class="page-card" v-loading="loadingDict">
    <div class="page-header">
      <div>
        <h3 class="page-title">新建交换任务</h3>
        <div class="page-subtitle">按向导配置数据源、字段映射与算子、目标端与调度策略</div>
      </div>
      <el-button :icon="Back" @click="$router.push('/task')">返回任务列表</el-button>
    </div>

    <el-steps :active="step" align-center finish-status="success" style="margin-bottom: 22px">
      <el-step title="选择数据源" description="确定源端与源表" />
      <el-step title="字段映射与算子" description="配置转换规则" />
      <el-step title="目标与调度" description="写入目标并设置调度" />
    </el-steps>

    <!-- 步骤 1 -->
    <div v-show="step === 0">
      <el-form ref="step0Ref" :model="form" :rules="step0Rules" label-width="110px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="任务名称" prop="taskName">
              <el-input v-model="form.taskName" placeholder="如：企业基础信息全量同步" maxlength="80" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="任务编码" prop="taskCode">
              <el-input v-model="form.taskCode" placeholder="如：TASK-ENTERPRISE-SYNC（留空自动生成）" maxlength="60" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="任务类型">
              <el-select v-model="form.taskType" style="width: 100%">
                <el-option label="全量同步" value="FULL" />
                <el-option label="增量同步" value="INCREMENT" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="源数据源">
              <el-select v-model="form.sourceId" filterable clearable placeholder="选择数据源（可空）" style="width: 100%">
                <el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="源端类型" prop="sourceType">
              <el-select v-model="form.sourceType" style="width: 100%">
                <el-option label="平台内置库表" value="H2_TABLE" />
                <el-option label="外部 JDBC 表" value="JDBC_TABLE" />
                <el-option label="CSV 文件" value="CSV_FILE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="form.sourceType === 'CSV_FILE' ? '源文件路径' : '源表名'" prop="sourceTable">
              <el-input
                v-model="form.sourceTable"
                :placeholder="form.sourceType === 'CSV_FILE' ? '/data/source/enterprise.csv' : 'demo_enterprise'"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="过滤条件">
              <el-input v-model="form.filterCondition" placeholder="可选，如 status = 'NORMAL'" />
            </el-form-item>
          </el-col>
          <template v-if="form.taskType === 'INCREMENT' && form.sourceType !== 'CSV_FILE'">
            <el-col :span="12">
              <el-form-item label="增量字段" prop="sourceIncrementColumn">
                <el-input v-model="form.sourceIncrementColumn" placeholder="如 update_time 或 id（时间戳/自增字段）" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="增量起点">
                <el-input v-model="form.sourceIncrementStart" placeholder="可选，如 2026-01-01 00:00:00 或 10000" />
              </el-form-item>
            </el-col>
          </template>
          <el-col :span="24">
            <el-form-item label="任务描述">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="300" show-word-limit placeholder="描述任务用途与数据流向" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </div>

    <!-- 步骤 2 -->
    <div v-show="step === 1">
      <div class="flex-between mb-12">
        <h4 class="section-title" style="margin: 0">字段映射</h4>
        <div class="toolbar">
          <el-button size="small" :icon="Plus" @click="addMapping">添加映射</el-button>
          <el-button size="small" :icon="MagicStick" @click="autoMapping">生成示例映射</el-button>
        </div>
      </div>
      <el-table :data="form.mapping" size="small" border empty-text="请添加至少一条字段映射">
        <el-table-column label="源字段" width="170">
          <template #default="{ row }">
            <el-input v-model="row.sourceField" size="small" placeholder="source_field" />
          </template>
        </el-table-column>
        <el-table-column label="目标字段" width="170">
          <template #default="{ row }">
            <el-input v-model="row.targetField" size="small" placeholder="target_field" />
          </template>
        </el-table-column>
        <el-table-column label="转换规则" width="160">
          <template #default="{ row }">
            <el-select v-model="row.transform" size="small" clearable placeholder="直接映射">
              <el-option label="直接映射" value="" />
              <el-option label="转大写 UPPER" value="UPPER" />
              <el-option label="转小写 LOWER" value="LOWER" />
              <el-option label="手机号脱敏 MASK_PHONE" value="MASK_PHONE" />
              <el-option label="身份证脱敏 MASK_IDCARD" value="MASK_IDCARD" />
              <el-option label="日期转换 TO_DATE" value="TO_DATE" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="脱敏算法" width="150">
          <template #default="{ row }">
            <el-select v-model="row.maskType" size="small" clearable placeholder="不脱敏">
              <el-option label="遮蔽 MASK" value="MASK" />
              <el-option label="替换 REPLACE" value="REPLACE" />
              <el-option label="哈希 HASH" value="HASH" />
              <el-option label="泛化 GENERALIZE" value="GENERALIZE" />
              <el-option label="扰动 PERTURB" value="PERTURB" />
              <el-option label="加密 ENCRYPT" value="ENCRYPT" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="脱敏参数" width="140">
          <template #default="{ row }">
            <el-input v-model="row.maskParam" size="small" placeholder="如 3,4" />
          </template>
        </el-table-column>
        <el-table-column label="" width="56" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" :icon="Delete" @click="form.mapping.splice($index, 1)" />
          </template>
        </el-table-column>
      </el-table>

      <div class="flex-between mb-12 mt-12">
        <h4 class="section-title" style="margin: 0">加工算子链</h4>
        <el-button size="small" :icon="Plus" @click="addOperator">添加算子</el-button>
      </div>
      <el-table :data="form.operators" size="small" border empty-text="未配置算子，数据将按原样写入">
        <el-table-column label="#" type="index" width="50" />
        <el-table-column label="算子" width="220">
          <template #default="{ row }">
            <el-select v-model="row.type" size="small" filterable allow-create placeholder="选择算子">
              <el-option v-for="op in operators" :key="op.code" :label="`${op.name}（${op.code}）`" :value="op.code" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="参数（JSON）" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.paramsText" size="small" placeholder='如 {"field":"phone"}' />
          </template>
        </el-table-column>
        <el-table-column label="" width="56" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" :icon="Delete" @click="form.operators.splice($index, 1)" />
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-12">
        <el-button size="small" :icon="VideoPlay" :loading="previewing" @click="doPreview">试运行（样例数据）</el-button>
        <span class="muted" style="margin-left: 8px">使用下方样例行执行映射与算子，验证转换结果</span>
      </div>
      <el-input
        v-model="sampleJson"
        type="textarea"
        :rows="3"
        class="mt-8"
        placeholder='样例行 JSON，如 [{"name":"张三","phone":"13800000000"}]'
      />
      <el-table v-if="previewRows.length" :data="previewRows" size="small" border class="mt-8" max-height="260">
        <el-table-column v-for="col in previewCols" :key="col" :prop="col" :label="col" min-width="120" show-overflow-tooltip />
      </el-table>
      <div v-else-if="previewed" class="muted mt-8">试运行无返回数据</div>
    </div>

    <!-- 步骤 3 -->
    <div v-show="step === 2">
      <el-form ref="step2Ref" :model="form" :rules="step2Rules" label-width="110px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="目标类型" prop="targetType">
              <el-select v-model="form.targetType" style="width: 100%">
                <el-option label="平台内置库表" value="H2_TABLE" />
                <el-option label="外部 JDBC 表" value="JDBC_TABLE" />
                <el-option label="CSV 文件" value="CSV_FILE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-if="form.targetType === 'JDBC_TABLE'" :span="12">
            <el-form-item label="目标数据源" prop="targetId">
              <el-select v-model="form.targetId" filterable placeholder="选择外部目标数据源" style="width: 100%">
                <el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="form.targetType === 'CSV_FILE' ? '目标文件路径' : '目标表名'" prop="targetTable">
              <el-input
                v-model="form.targetTable"
                :placeholder="form.targetType === 'CSV_FILE' ? '/data/target/enterprise.csv' : 't_enterprise'"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="调度 cron">
              <el-input v-model="form.scheduleCron" placeholder="如：0 0 2 * * ?（每天 2 点，留空为手工触发）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="创建后启用">
              <el-switch v-model="form.enabled" inline-prompt active-text="启用" inactive-text="停用" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="配置预览">
              <pre class="code-block" style="width: 100%; max-height: 260px">{{ definitionPreview }}</pre>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </div>

    <div class="toolbar mt-12" style="justify-content: flex-end">
      <el-button v-if="step > 0" @click="step -= 1">上一步</el-button>
      <el-button v-if="step < 2" type="primary" @click="next">下一步</el-button>
      <el-button v-else type="primary" :loading="submitting" @click="submit">提交创建</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back, Plus, Delete, MagicStick, VideoPlay } from '@element-plus/icons-vue'
import request from '@/api/request'
import { listOf, pick } from '@/api/helpers'

const route = useRoute()
const router = useRouter()

const step = ref(0)
const loadingDict = ref(false)
const submitting = ref(false)
const previewing = ref(false)
const previewed = ref(false)
const previewCols = ref([])
const previewRows = ref([])
const sampleJson = ref('[{"name":"张三","phone":"13800000000","idCard":"110101199001011234"}]')
const sources = ref([])
const operators = ref([])
const step0Ref = ref()
const step2Ref = ref()

const form = reactive({
  taskName: '',
  taskCode: '',
  taskType: 'FULL',
  sourceId: null,
  sourceType: 'H2_TABLE',
  sourceTable: '',
  sourceIncrementColumn: '',
  sourceIncrementStart: '',
  filterCondition: '',
  remark: '',
  mapping: [],
  operators: [],
  targetType: 'H2_TABLE',
  targetTable: '',
  targetId: null,
  scheduleCron: '',
  enabled: true
})

const step0Rules = {
  taskName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  sourceType: [{ required: true, message: '请选择源端类型', trigger: 'change' }],
  sourceTable: [{ required: true, message: '请填写源表名或文件路径', trigger: 'blur' }],
  sourceIncrementColumn: [{ required: true, message: '增量任务必须填写增量字段', trigger: 'blur' }]
}
const step2Rules = {
  targetType: [{ required: true, message: '请选择目标类型', trigger: 'change' }],
  targetTable: [{ required: true, message: '请填写目标表名或文件路径', trigger: 'blur' }],
  targetId: [{ required: true, message: '外部 JDBC 目标必须选择数据源', trigger: 'change' }]
}

const definitionPreview = computed(() =>
  JSON.stringify(
    {
      taskName: form.taskName,
      taskCode: form.taskCode || '(自动生成)',
      taskType: form.taskType,
      source: {
        sourceId: form.sourceId,
        sourceType: form.sourceType,
        tableName: form.sourceType !== 'CSV_FILE' ? form.sourceTable : undefined,
        filePath: form.sourceType === 'CSV_FILE' ? form.sourceTable : undefined,
        incrementColumn: form.taskType === 'INCREMENT' && form.sourceType !== 'CSV_FILE' ? form.sourceIncrementColumn : undefined,
        incrementStart: form.taskType === 'INCREMENT' && form.sourceType !== 'CSV_FILE' ? form.sourceIncrementStart || undefined : undefined,
        filterCondition: form.filterCondition || undefined
      },
      mapping: form.mapping,
      operators: normalizedOperators(),
      target: {
        targetType: form.targetType,
        targetId: form.targetType === 'JDBC_TABLE' ? form.targetId : undefined,
        tableName: form.targetType !== 'CSV_FILE' ? form.targetTable : undefined,
        filePath: form.targetType === 'CSV_FILE' ? form.targetTable : undefined
      },
      schedule: { cron: form.scheduleCron || undefined, enabled: form.enabled }
    },
    null,
    2
  )
)

function addMapping() {
  form.mapping.push({ sourceField: '', targetField: '', transform: '', maskType: '', maskParam: '' })
}

function addOperator() {
  form.operators.push({ type: '', paramsText: '' })
}

function autoMapping() {
  form.mapping = [
    { sourceField: 'id', targetField: 'id', transform: '', maskType: '', maskParam: '' },
    { sourceField: 'name', targetField: 'name', transform: '', maskType: '', maskParam: '' },
    { sourceField: 'phone', targetField: 'phone', transform: '', maskType: 'MASK', maskParam: '3,4' }
  ]
  ElMessage.success('已生成示例字段映射，请按实际表结构调整')
}

function normalizedOperators() {
  return form.operators
    .filter((op) => op.type)
    .map((op) => {
      let params = {}
      if (op.paramsText) {
        try {
          params = JSON.parse(op.paramsText)
        } catch (e) {
          params = {}
        }
      }
      return { type: op.type, params }
    })
}

async function next() {
  if (step.value === 0) {
    try {
      await step0Ref.value.validate()
    } catch (e) {
      return
    }
    if (!form.mapping.length) addMapping()
  }
  if (step.value === 1 && !form.mapping.some((m) => m.sourceField && m.targetField)) {
    // 允许空映射（原样透传），仅提示
    ElMessage.info('未配置字段映射时将按源端字段原样写入目标')
  }
  step.value += 1
}

function normalizeOperators(data) {
  const list = listOf(data.operators || data.list || data)
  if (list.length) {
    return list
      .map((o) => {
        if (typeof o === 'string') return { code: o, name: o }
        return { code: pick(o, ['code', 'type', 'name', 'value'], ''), name: pick(o, ['name', 'label', 'description', 'code'], '') }
      })
      .filter((o) => o.code)
  }
  return []
}

const DEFAULT_OPERATORS = [
  { code: 'TRIM', name: '去除空格' },
  { code: 'DEDUP', name: '去重' },
  { code: 'FILTER', name: '行过滤' },
  { code: 'REGEX_REPLACE', name: '正则替换' },
  { code: 'MASK', name: '脱敏' },
  { code: 'CONVERT_TYPE', name: '类型转换' },
  { code: 'NORMALIZE_DATE', name: '日期规范化' },
  { code: 'VALIDATE_PHONE', name: '手机号校验' },
  { code: 'MERGE_FIELD', name: '字段拼接' },
  { code: 'SPLIT_FIELD', name: '字段拆分' },
  { code: 'ADD_CONST', name: '常量列' },
  { code: 'RENAME', name: '字段重命名' },
  { code: 'LIMIT', name: '限制行数' }
]

async function doPreview() {
  let sampleRows = []
  try {
    sampleRows = JSON.parse(sampleJson.value || '[]')
    if (!Array.isArray(sampleRows)) throw new Error('样例需为数组')
  } catch (e) {
    ElMessage.warning('样例行 JSON 解析失败，请检查格式')
    return
  }
  previewing.value = true
  previewed.value = true
  try {
    const res = await request.post('/task/v1/preview', {
      sampleRows,
      mapping: form.mapping.filter((m) => m.targetField),
      operators: normalizedOperators(),
      filterCondition: form.filterCondition || undefined
    })
    const d = res.data || {}
    const rows = listOf(d.rows || d)
    const cols = Array.isArray(d.columns) ? d.columns : rows.length && typeof rows[0] === 'object' ? Object.keys(rows[0]) : []
    previewCols.value = cols
    previewRows.value = rows
    ElMessage.success(`试运行完成，输出 ${rows.length} 行`)
  } catch (e) {
    previewRows.value = []
    previewCols.value = []
    ElMessage.warning('试运行失败，请检查映射/算子配置')
  } finally {
    previewing.value = false
  }
}

async function submit() {
  try {
    await step2Ref.value.validate()
  } catch (e) {
    return
  }
  submitting.value = true
  try {
    const payload = {
      taskName: form.taskName,
      taskCode: form.taskCode || `TASK-${Date.now()}`,
      taskType: form.taskType,
      sourceId: form.sourceId,
      sourceType: form.sourceType,
      sourceName: form.sourceType !== 'CSV_FILE' ? form.sourceTable : undefined,
      sourceTable: form.sourceType !== 'CSV_FILE' ? form.sourceTable : undefined,
      sourceFilePath: form.sourceType === 'CSV_FILE' ? form.sourceTable : undefined,
      sourceIncrementColumn: form.taskType === 'INCREMENT' && form.sourceType !== 'CSV_FILE' ? form.sourceIncrementColumn : undefined,
      sourceIncrementStart: form.taskType === 'INCREMENT' && form.sourceType !== 'CSV_FILE' ? form.sourceIncrementStart || undefined : undefined,
      targetType: form.targetType,
      targetId: form.targetType === 'JDBC_TABLE' ? form.targetId : undefined,
      targetTable: form.targetType !== 'CSV_FILE' ? form.targetTable : undefined,
      targetFilePath: form.targetType === 'CSV_FILE' ? form.targetTable : undefined,
      filterCondition: form.filterCondition || undefined,
      scheduleCron: form.scheduleCron || undefined,
      enabled: form.enabled,
      status: form.enabled ? 'ENABLED' : 'DISABLED',
      remark: form.remark,
      mapping: form.mapping.filter((m) => m.targetField),
      operators: normalizedOperators()
    }
    const res = await request.post('/task/v1/tasks', payload)
    const id = res.data?.id
    ElMessage.success('任务创建成功')
    if (id) router.push(`/task/${id}`)
    else router.push('/task')
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

async function loadDicts() {
  loadingDict.value = true
  try {
    const res = await request.get('/datasource/v1/sources', { params: { pageNum: 1, pageSize: 200 } })
    sources.value = listOf(res.data).map((s) => ({ id: s.id, name: pick(s, ['sourceName', 'name'], `数据源 #${s.id}`) }))
  } catch (e) {
    sources.value = []
  }
  try {
    const res = await request.get('/task/v1/operators')
    const list = normalizeOperators(res.data || {})
    operators.value = list.length ? list : DEFAULT_OPERATORS
  } catch (e) {
    operators.value = DEFAULT_OPERATORS
  }
  loadingDict.value = false
}

onMounted(() => {
  loadDicts()
  addMapping()
  const sourceId = route.query.sourceId
  if (sourceId) form.sourceId = Number(sourceId) || sourceId
})
</script>
