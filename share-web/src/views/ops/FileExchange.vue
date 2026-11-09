<template>
  <div class="page-card" v-loading="loading">
    <div class="page-header">
      <div>
        <h3 class="page-title">文件交换</h3>
        <div class="page-subtitle">文件上传下载、分片断点续传、完整性校验与定时传输任务</div>
      </div>
      <div class="toolbar">
        <el-button type="primary" :icon="Upload" @click="openUpload">上传文件</el-button>
        <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <div class="stat-grid stat-grid--4 mb-12">
      <div class="stat-card">
        <div class="stat-label">文件总数</div>
        <div class="stat-value">{{ fmtNumber(pick(stats, ['fileCount'], '-')) }}</div>
        <div class="muted">今日上传 {{ fmtNumber(pick(stats, ['todayUploads'], 0)) }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">文件总量</div>
        <div class="stat-value">{{ pick(stats, ['totalSizeText'], sizeText(pick(stats, ['totalBytes'], '-'))) }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">传输成功率</div>
        <div class="stat-value">{{ pick(stats, ['transferSuccessRate'], '-') }}%</div>
        <div class="muted">成功 {{ fmtNumber(pick(stats, ['successTransfers'], 0)) }} / 失败 {{ fmtNumber(pick(stats, ['failedTransfers'], 0)) }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">传输任务</div>
        <div class="stat-value">{{ fmtNumber(pick(stats, ['transferTaskCount'], '-')) }}</div>
        <div class="muted">启用 {{ fmtNumber(pick(stats, ['enabledTransferTasks'], 0)) }}</div>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane label="文件列表" name="files">
        <el-form inline class="filter-bar" @submit.prevent>
          <el-form-item label="关键词">
            <el-input v-model="fileQuery.keyword" placeholder="文件名 / 上传人 / MD5" clearable @keyup.enter="searchFiles" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="searchFiles">查询</el-button>
            <el-button :icon="RefreshLeft" @click="resetFiles">重置</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="files" size="small" stripe border empty-text="暂无文件">
          <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['fileName', 'name'], '-') }}</template>
          </el-table-column>
          <el-table-column label="大小" width="110" align="right">
            <template #default="{ row }">{{ pick(row, ['fileSizeText'], sizeText(pick(row, ['fileSize', 'size'], '-'))) }}</template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ pick(row, ['extName', 'fileType'], '-') }}</template>
          </el-table-column>
          <el-table-column label="上传人" width="120">
            <template #default="{ row }">{{ pick(row, ['uploader', 'uploaderName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="上传时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['createTime', 'uploadTime'])) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(row.status || 'SUCCESS')">{{ fileStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="MD5" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['md5'], '-') }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="download(row)">下载</el-button>
              <el-button link type="success" :loading="verifyingId === row.id" @click="verify(row)">校验</el-button>
              <el-button link type="danger" @click="removeFile(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="filePage"
            v-model:page-size="fileSize2"
            background
            layout="total, sizes, prev, pager, next"
            :total="fileTotal"
            :page-sizes="[10, 20, 50]"
            @current-change="loadFiles"
            @size-change="searchFiles"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="传输任务" name="transfers">
        <div class="flex-between mb-12">
          <span class="muted">定时收发任务，支持目录监听（watchPath）与手工立即执行</span>
          <el-button size="small" type="primary" :icon="Plus" @click="openTransfer()">新建传输任务</el-button>
        </div>
        <el-table :data="transfers" size="small" stripe border empty-text="暂无传输任务">
          <el-table-column label="任务名称" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['taskName', 'name'], '-') }}</template>
          </el-table-column>
          <el-table-column label="源路径" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['sourcePath'], '-') }}</template>
          </el-table-column>
          <el-table-column label="目标路径" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['targetPath'], '-') }}</template>
          </el-table-column>
          <el-table-column label="监听目录" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['watchPath'], '-') }}</template>
          </el-table-column>
          <el-table-column label="调度" width="140">
            <template #default="{ row }">{{ pick(row, ['scheduleCron'], '手工') }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近执行" width="200">
            <template #default="{ row }">
              <div class="muted">{{ fmtDateTime(pick(row, ['lastRunTime'])) }}</div>
              <div class="flex-gap">
                <el-tag v-if="row.lastStatus" size="small" :type="tagType(row.lastStatus)">{{ row.lastStatus }}</el-tag>
                <span class="muted">{{ pick(row, ['lastMessage'], '') }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="success" :loading="runningTransferId === row.id" @click="runTransfer(row)">立即执行</el-button>
              <el-button link type="primary" @click="openTransfer(row)">编辑</el-button>
              <el-button link type="danger" @click="removeTransfer(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 上传 -->
    <el-dialog v-model="uploadVisible" title="上传文件（分片断点续传）" width="580px" append-to-body :close-on-click-modal="false">
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        :on-change="onFileChange"
        :disabled="uploading"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将文件拖到此处，或<em>点击选择文件</em></div>
        <template #tip>
          <div class="muted">按 2MB 分片上传，展示实时进度；上传完成后自动合并并校验</div>
        </template>
      </el-upload>
      <div v-if="uploadFileName" class="mt-12">
        <div class="flex-between">
          <span>{{ uploadFileName }}</span>
          <span class="muted">{{ sizeText(uploadFileSize) }}</span>
        </div>
        <el-progress :percentage="uploadPercent" :status="uploadStatus" class="mt-8" />
        <div class="muted mt-8">{{ uploadTip }}</div>
      </div>
      <template #footer>
        <el-button @click="uploadVisible = false" :disabled="uploading">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 校验结果 -->
    <el-dialog v-model="verifyVisible" title="文件完整性校验" width="520px" append-to-body>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="文件">{{ verifyResult.fileName }}</el-descriptions-item>
        <el-descriptions-item label="校验结果">
          <el-tag :type="verifyResult.ok ? 'success' : 'danger'">{{ verifyResult.ok ? '校验通过' : '校验不一致' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="登记 MD5">{{ verifyResult.expectedMd5 }}</el-descriptions-item>
        <el-descriptions-item label="实际 MD5">{{ verifyResult.actualMd5 }}</el-descriptions-item>
        <el-descriptions-item label="登记 SHA256">{{ verifyResult.expectedSha256 }}</el-descriptions-item>
        <el-descriptions-item label="实际 SHA256">{{ verifyResult.actualSha256 }}</el-descriptions-item>
        <el-descriptions-item label="说明">{{ verifyResult.message }}</el-descriptions-item>
      </el-descriptions>
      <template #footer><el-button type="primary" @click="verifyVisible = false">关闭</el-button></template>
    </el-dialog>

    <!-- 传输任务编辑 -->
    <el-dialog v-model="transferVisible" :title="transferForm.id ? '编辑传输任务' : '新建传输任务'" width="660px" append-to-body>
      <el-form ref="transferRef" :model="transferForm" :rules="transferRules" label-width="100px">
        <el-form-item label="任务名称" prop="taskName">
          <el-input v-model="transferForm.taskName" placeholder="如：每日信用数据文件归集" />
        </el-form-item>
        <el-form-item label="传输模式">
          <el-select v-model="transferForm.transferMode" style="width: 100%">
            <el-option label="单文件 FILE" value="FILE" />
            <el-option label="目录 DIR" value="DIR" />
          </el-select>
        </el-form-item>
        <el-form-item label="源路径" prop="sourcePath">
          <el-input v-model="transferForm.sourcePath" placeholder="如：/data/local/credit" />
        </el-form-item>
        <el-form-item label="目标路径" prop="targetPath">
          <el-input v-model="transferForm.targetPath" placeholder="如：/data/exchange/credit" />
        </el-form-item>
        <el-form-item label="监听目录">
          <el-input v-model="transferForm.watchPath" placeholder="可选：目录监听路径 watchPath" />
        </el-form-item>
        <el-form-item label="调度 cron">
          <el-input v-model="transferForm.scheduleCron" placeholder="如：0 30 1 * * ?" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="transferForm.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="transferForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingTransfer" @click="saveTransfer">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload, UploadFilled, Refresh, Search, RefreshLeft, Plus } from '@element-plus/icons-vue'
import request from '@/api/request'
import { clean, confirmBox, fmtDateTime, fmtNumber, listOf, pageOf, pick, saveBlob, tagType } from '@/api/helpers'

const loading = ref(false)
const tab = ref('files')
const stats = ref({})
const files = ref([])
const fileTotal = ref(0)
const filePage = ref(1)
const fileSize2 = ref(10)
const fileQuery = reactive({ keyword: '' })
const verifyingId = ref(null)

const transfers = ref([])
const runningTransferId = ref(null)
const transferVisible = ref(false)
const savingTransfer = ref(false)
const transferRef = ref()
const transferForm = reactive(emptyTransfer())
const transferRules = {
  taskName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  sourcePath: [{ required: true, message: '请输入源路径', trigger: 'blur' }],
  targetPath: [{ required: true, message: '请输入目标路径', trigger: 'blur' }]
}

const uploadVisible = ref(false)
const uploading = ref(false)
const uploadFileName = ref('')
const uploadFileSize = ref(0)
const uploadPercent = ref(0)
const uploadStatus = ref('')
const uploadTip = ref('')
const CHUNK_SIZE = 2 * 1024 * 1024

const verifyVisible = ref(false)
const verifyResult = reactive({ ok: true, fileName: '', expectedMd5: '-', actualMd5: '-', expectedSha256: '-', actualSha256: '-', message: '-' })

const FILE_STATUS = { SUCCESS: '已上传', VERIFIED: '已校验', CORRUPTED: '已损坏', UPLOADING: '上传中', FAILED: '失败' }

function emptyTransfer() {
  return { id: null, taskName: '', transferMode: 'FILE', sourcePath: '', targetPath: '', watchPath: '', scheduleCron: '', enabled: true, remark: '' }
}

function fileStatusText(v) {
  return FILE_STATUS[String(v || 'SUCCESS').toUpperCase()] || v || '已上传'
}

function sizeText(v) {
  if (v === null || v === undefined || v === '' || v === '-') return '-'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

async function loadStats() {
  try {
    const res = await request.get('/file/v1/stats')
    stats.value = res.data || {}
  } catch (e) {
    stats.value = {}
  }
}

async function loadFiles() {
  loading.value = true
  try {
    const res = await request.get('/file/v1/files', {
      params: clean({ pageNum: filePage.value, pageSize: fileSize2.value, keyword: fileQuery.keyword })
    })
    files.value = listOf(res.data)
    fileTotal.value = pageOf(res).total
  } catch (e) {
    files.value = []
    fileTotal.value = 0
  } finally {
    loading.value = false
  }
}

function searchFiles() {
  filePage.value = 1
  loadFiles()
}

function resetFiles() {
  fileQuery.keyword = ''
  searchFiles()
}

async function loadTransfers() {
  try {
    const res = await request.get('/file/v1/transfer-tasks', { params: { pageNum: 1, pageSize: 100 } })
    transfers.value = listOf(res.data)
  } catch (e) {
    transfers.value = []
  }
}

function reloadAll() {
  loadStats()
  loadFiles()
  loadTransfers()
}

function onTab(name) {
  if (name === 'files') {
    loadFiles()
    loadStats()
  }
  if (name === 'transfers') loadTransfers()
}

async function download(row) {
  try {
    const blob = await request.get(`/file/v1/files/${row.id}/download`, { responseType: 'blob' })
    saveBlob(blob, pick(row, ['fileName', 'name'], 'download'))
  } catch (e) {
    ElMessage.error('文件下载失败')
  }
}

async function verify(row) {
  verifyingId.value = row.id
  try {
    const res = await request.post(`/file/v1/files/${row.id}/verify`)
    const d = res.data || {}
    verifyResult.ok = d.verified !== undefined ? !!d.verified : !!(d.md5Match && d.sha256Match)
    verifyResult.fileName = pick(row, ['fileName', 'name'], '-')
    verifyResult.expectedMd5 = pick(d, ['expectedMd5'], '-')
    verifyResult.actualMd5 = pick(d, ['actualMd5'], '-')
    verifyResult.expectedSha256 = pick(d, ['expectedSha256'], '-')
    verifyResult.actualSha256 = pick(d, ['actualSha256'], '-')
    verifyResult.message = pick(d, ['message'], verifyResult.ok ? '校验通过' : '校验不一致')
    verifyVisible.value = true
  } catch (e) {
    ElMessage.warning('校验接口暂不可用')
  } finally {
    verifyingId.value = null
  }
}

async function removeFile(row) {
  const ok = await confirmBox(`确认删除文件「${pick(row, ['fileName', 'name'], '')}」？`, '删除文件', 'error')
  if (!ok) return
  try {
    await request.delete(`/file/v1/files/${row.id}`)
    ElMessage.success('文件已删除')
    loadFiles()
    loadStats()
  } catch (e) {
    // 拦截器已提示
  }
}

function openUpload() {
  uploadFileName.value = ''
  uploadFileSize.value = 0
  uploadPercent.value = 0
  uploadStatus.value = ''
  uploadTip.value = ''
  uploadVisible.value = true
}

function uuid() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID().replace(/-/g, '')
  return `up${Date.now()}${Math.floor(Math.random() * 1e6)}`
}

async function onFileChange(uploadFile) {
  const raw = uploadFile?.raw
  if (!raw) return
  uploadFileName.value = raw.name
  uploadFileSize.value = raw.size
  uploadPercent.value = 0
  uploadTip.value = '正在计算分片…'
  uploadStatus.value = ''
  uploading.value = true
  try {
    const uploadId = uuid()
    const totalChunks = Math.max(1, Math.ceil(raw.size / CHUNK_SIZE))
    for (let i = 0; i < totalChunks; i += 1) {
      const chunk = raw.slice(i * CHUNK_SIZE, Math.min((i + 1) * CHUNK_SIZE, raw.size))
      const form = new FormData()
      form.append('uploadId', uploadId)
      form.append('index', String(i))
      form.append('total', String(totalChunks))
      form.append('file', chunk, `${raw.name}.part${i}`)
      await request.post('/file/v1/uploads/chunk', form, { timeout: 120000 })
      uploadPercent.value = Math.min(99, Math.round(((i + 1) / totalChunks) * 100))
      uploadTip.value = `已上传分片 ${i + 1}/${totalChunks}`
    }
    uploadTip.value = '正在合并分片并校验…'
    await request.post('/file/v1/uploads/complete', {
      uploadId,
      fileName: raw.name
    })
    uploadPercent.value = 100
    uploadStatus.value = 'success'
    uploadTip.value = '上传完成'
    ElMessage.success('文件上传成功')
    loadFiles()
    loadStats()
  } catch (e) {
    uploadStatus.value = 'exception'
    uploadTip.value = '上传失败，请确认后端分片接口已就绪'
  } finally {
    uploading.value = false
  }
}

function openTransfer(row) {
  if (row) {
    Object.assign(transferForm, {
      id: row.id,
      taskName: pick(row, ['taskName', 'name'], ''),
      transferMode: pick(row, ['transferMode'], 'FILE'),
      sourcePath: pick(row, ['sourcePath'], ''),
      targetPath: pick(row, ['targetPath'], ''),
      watchPath: pick(row, ['watchPath'], ''),
      scheduleCron: pick(row, ['scheduleCron'], ''),
      enabled: row.enabled !== false,
      remark: pick(row, ['remark'], '')
    })
  } else {
    Object.assign(transferForm, emptyTransfer())
  }
  transferVisible.value = true
}

async function saveTransfer() {
  try {
    await transferRef.value.validate()
  } catch (e) {
    return
  }
  savingTransfer.value = true
  try {
    const payload = {
      taskName: transferForm.taskName,
      transferMode: transferForm.transferMode,
      sourceType: 'LOCAL',
      targetType: 'LOCAL',
      sourcePath: transferForm.sourcePath,
      targetPath: transferForm.targetPath,
      watchPath: transferForm.watchPath,
      scheduleCron: transferForm.scheduleCron,
      enabled: transferForm.enabled,
      remark: transferForm.remark
    }
    if (transferForm.id) await request.put(`/file/v1/transfer-tasks/${transferForm.id}`, payload)
    else await request.post('/file/v1/transfer-tasks', payload)
    ElMessage.success('传输任务已保存')
    transferVisible.value = false
    loadTransfers()
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingTransfer.value = false
  }
}

async function runTransfer(row) {
  runningTransferId.value = row.id
  try {
    await request.post(`/file/v1/transfer-tasks/${row.id}/run`)
    ElMessage.success('传输任务已执行')
    loadTransfers()
    loadStats()
  } catch (e) {
    // 拦截器已提示
  } finally {
    runningTransferId.value = null
  }
}

async function removeTransfer(row) {
  const ok = await confirmBox('确认删除该传输任务？', '删除传输任务', 'error')
  if (!ok) return
  try {
    await request.delete(`/file/v1/transfer-tasks/${row.id}`)
    ElMessage.success('传输任务已删除')
    loadTransfers()
  } catch (e) {
    // 拦截器已提示
  }
}

onMounted(reloadAll)
</script>
