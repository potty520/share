<template>
  <div v-loading="loading">
    <div class="page-card">
      <div class="page-header">
        <div>
          <h3 class="page-title">数据目录</h3>
          <div class="page-subtitle">按主题分类检索可共享的数据资源，查看详情并提交共享申请</div>
        </div>
        <div class="toolbar">
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="card">卡片</el-radio-button>
          </el-radio-group>
          <el-button type="primary" :icon="Search" @click="search">查询</el-button>
          <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
        </div>
      </div>

      <div class="stat-grid mb-12">
        <div class="stat-card">
          <div class="stat-label">资源总数</div>
          <div class="stat-value">{{ fmtNumber(stats.total) }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">已发布</div>
          <div class="stat-value">{{ fmtNumber(stats.published) }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">待审核</div>
          <div class="stat-value">{{ fmtNumber(stats.pending) }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">涉敏资源</div>
          <div class="stat-value">{{ fmtNumber(stats.sensitive) }}</div>
        </div>
      </div>

      <el-form inline class="filter-bar" @submit.prevent>
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" placeholder="名称 / 编码 / 描述" clearable @keyup.enter="search" />
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
        <el-form-item label="部门">
          <el-select v-model="query.deptId" clearable placeholder="全部部门" style="width: 160px">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="共享属性">
          <el-select v-model="query.shareType" clearable placeholder="全部" style="width: 130px">
            <el-option label="无条件共享" value="UNCONDITIONAL" />
            <el-option label="有条件共享" value="CONDITIONAL" />
            <el-option label="不予共享" value="NO_SHARE" />
          </el-select>
        </el-form-item>
        <el-form-item label="密级">
          <el-select v-model="query.secretLevel" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="l in levelOptions" :key="l.value" :label="l.label" :value="l.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="query.tag" placeholder="标签" clearable style="width: 120px" @keyup.enter="search" />
        </el-form-item>
      </el-form>

      <!-- 表格视图 -->
      <el-table v-if="viewMode === 'table'" :data="rows" stripe border empty-text="暂无匹配的数据资源">
        <el-table-column prop="name" label="资源名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-link" @click="goDetail(row)">{{ nameOf(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['classifyName', 'classify', 'categoryName'], '-') }}</template>
        </el-table-column>
        <el-table-column label="提供部门" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ pick(row, ['deptName', 'departmentName', 'providerDept'], '-') }}</template>
        </el-table-column>
        <el-table-column label="共享属性" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="shareTypeTag(row.shareType)">{{ shareTypeText(row.shareType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="密级" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="levelTag(levelOf(row))">{{ levelText(levelOf(row)) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ fmtDateTime(pick(row, ['updateTime', 'updatedAt', 'publishTime'])) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">详情</el-button>
            <el-button link type="primary" :disabled="!canApply(row)" @click="goApply(row)">申请</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 卡片视图 -->
      <div v-else>
        <div v-if="rows.length" class="card-grid">
          <div v-for="row in rows" :key="row.id" class="catalog-card" @click="goDetail(row)">
            <div class="flex-between">
              <span class="catalog-name">
                <el-icon><Files /></el-icon>
                {{ nameOf(row) }}
              </span>
              <el-tag size="small" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
            </div>
            <div class="muted catalog-desc">{{ pick(row, ['summary', 'description', 'remark'], '暂无资源描述') }}</div>
            <div class="tag-list mt-8">
              <el-tag size="small" effect="plain">{{ pick(row, ['classifyName', 'classify'], '未分类') }}</el-tag>
              <el-tag size="small" effect="plain" :type="shareTypeTag(row.shareType)">{{ shareTypeText(row.shareType) }}</el-tag>
              <el-tag size="small" :type="levelTag(levelOf(row))">{{ levelText(levelOf(row)) }}</el-tag>
            </div>
            <div class="flex-between mt-12">
              <span class="muted">{{ pick(row, ['deptName', 'providerDept'], '—') }}</span>
              <el-button size="small" type="primary" plain @click.stop="goApply(row)">申请共享</el-button>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无匹配的数据资源" />
      </div>

      <div class="pager">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          background
          layout="total, sizes, prev, pager, next, jumper"
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
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, RefreshLeft } from '@element-plus/icons-vue'
import request from '@/api/request'
import { useAuthStore } from '@/store/auth'
import {
  clean,
  fmtDateTime,
  fmtNumber,
  listOf,
  pageOf,
  pick,
  tagType
} from '@/api/helpers'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const viewMode = ref('table')
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const query = reactive({ keyword: '', classifyId: null, deptId: null, shareType: '', secretLevel: null, tag: '' })
const stats = reactive({ total: '-', published: '-', pending: '-', sensitive: '-' })
const classifyTree = ref([])
const depts = ref([])

const levelOptions = [
  { label: '1 级 公开', value: 1 },
  { label: '2 级 内部', value: 2 },
  { label: '3 级 敏感', value: 3 },
  { label: '4 级 核心', value: 4 }
]

const STATUS_TEXT = {
  DRAFT: '草稿',
  PENDING: '待审核',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
  OFFLINE: '已下架'
}

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

function levelOf(row) {
  return pick(row, ['secretLevel', 'level'], '')
}

function levelText(v) {
  const n = Number(v)
  if (!n) return '-'
  const map = { 1: '1 级 公开', 2: '2 级 内部', 3: '3 级 敏感', 4: '4 级 核心' }
  return map[n] || `${n} 级`
}

function levelTag(v) {
  const n = Number(v)
  if (n >= 4) return 'danger'
  if (n === 3) return 'warning'
  if (n === 2) return 'info'
  return 'success'
}

function nameOf(row) {
  return pick(row, ['resourceName', 'name', 'title', 'catalogName'], `资源 #${row?.id ?? ''}`)
}

function canApply(row) {
  return auth.hasPerm('approval:apply') && String(row?.status || '').toUpperCase() === 'PUBLISHED'
}

function goDetail(row) {
  router.push(`/catalog/${row.id}`)
}

function goApply(row) {
  if (!canApply(row)) {
    ElMessage.warning('仅已发布资源可申请共享')
    return
  }
  router.push({ path: '/approval', query: { catalogId: row.id, catalogName: nameOf(row), action: 'apply' } })
}

function toTree(list) {
  const nodes = list.map((it) => ({
    value: it.id ?? it.value,
    label: it.name ?? it.label ?? it.title,
    children: it.children && it.children.length ? toTree(it.children) : undefined
  }))
  return nodes
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/catalog/v1/resources', {
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
  query.classifyId = null
  query.deptId = null
  query.shareType = ''
  query.level = null
  query.tag = ''
  search()
}

async function loadStats() {
  try {
    const res = await request.get('/catalog/v1/stats')
    const d = res.data || {}
    stats.total = pick(d, ['total', 'catalogTotal', 'totalCount'], '-')
    stats.published = pick(d, ['published', 'publishedCount'], '-')
    stats.pending = pick(d, ['pending', 'pendingCount'], '-')
    stats.sensitive = pick(d, ['sensitive', 'sensitiveCount', 'maskCount'], '-')
  } catch (e) {
    /* 统计接口未就绪时保持占位 */
  }
}

async function loadClassifies() {
  try {
    const res = await request.get('/catalog/v1/classifies')
    classifyTree.value = toTree(listOf(res.data))
  } catch (e) {
    classifyTree.value = []
  }
}

async function loadDepts() {
  try {
    const res = await request.get('/user/v1/depts')
    const flat = []
    const walk = (list, prefix = '') => {
      list.forEach((d) => {
        flat.push({ id: d.id, name: `${prefix}${d.name || d.deptName || ''}` })
        const children = d.children || d.childList || []
        if (children.length) walk(children, '　')
      })
    }
    walk(listOf(res.data))
    depts.value = flat
  } catch (e) {
    depts.value = []
  }
}

onMounted(() => {
  load()
  loadStats()
  loadClassifies()
  loadDepts()
})
</script>

<style scoped>
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.catalog-card {
  border: 1px solid var(--share-border);
  border-radius: 8px;
  padding: 14px;
  cursor: pointer;
  transition: all 0.15s;
  background: #fff;
}

.catalog-card:hover {
  border-color: var(--share-primary);
  box-shadow: 0 4px 16px rgba(74, 94, 229, 0.12);
}

.catalog-name {
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.catalog-desc {
  margin-top: 8px;
  height: 38px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
</style>
