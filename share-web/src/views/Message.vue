<template>
  <div class="page-card" v-loading="loading">
    <div class="page-header">
      <div>
        <h3 class="page-title">消息中心</h3>
        <div class="page-subtitle">站内通知、公告发布、外部通知通道与资源使用反馈</div>
      </div>
      <div class="toolbar">
        <el-button :disabled="!unreadCount" @click="readAll">全部标为已读</el-button>
        <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="onTab">
      <!-- 消息 -->
      <el-tab-pane label="消息" name="messages">
        <div class="flex-between mb-12">
          <el-radio-group v-model="unreadOnly" size="small" @change="loadMessages">
            <el-radio-button :value="false">全部消息</el-radio-button>
            <el-radio-button :value="true">未读（{{ unreadCount }}）</el-radio-button>
          </el-radio-group>
          <span class="muted">共 {{ messageTotal }} 条</span>
        </div>
        <el-table :data="messages" size="small" stripe border empty-text="暂无消息">
          <el-table-column label="" width="46" align="center">
            <template #default="{ row }">
              <el-badge v-if="!isRead(row)" is-dot type="danger" />
            </template>
          </el-table-column>
          <el-table-column label="标题" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span :style="{ fontWeight: isRead(row) ? 400 : 600 }">{{ pick(row, ['title', 'subject'], '系统消息') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="280" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['content', 'body', 'message'], '-') }}</template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ typeText(pick(row, ['type', 'msgType', 'bizType'], '')) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['createTime', 'sendTime'])) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="isRead(row)" @click="markRead(row)">标记已读</el-button>
            </template>
          </el-table-column>
        </el-table>        <div class="pager">
          <el-pagination
            v-model:current-page="messagePage"
            :page-size="messageSize"
            background
            layout="total, prev, pager, next"
            :total="messageTotal"
            @current-change="loadMessages"
          />
        </div>
      </el-tab-pane>

      <!-- 公告 -->
      <el-tab-pane label="公告" name="announcements">
        <div class="flex-between mb-12">
          <span class="muted">平台公告面向全部用户展示</span>
          <el-button size="small" type="primary" :icon="Plus" @click="openAnnouncement">发布公告</el-button>
        </div>
        <el-table :data="announcements" size="small" stripe border empty-text="暂无公告">
          <el-table-column label="标题" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['title', 'name'], '-') }}</template>
          </el-table-column>
          <el-table-column label="内容" min-width="300" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['contentPreview', 'content', 'body'], '-') }}</template>
          </el-table-column>
          <el-table-column label="级别" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.level === 'IMPORTANT' ? 'warning' : 'info'">{{ pick(row, ['level'], 'NORMAL') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发布人" width="120">
            <template #default="{ row }">{{ pick(row, ['publisherName', 'creatorName', 'createdBy'], '-') }}</template>
          </el-table-column>
          <el-table-column label="发布时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['createTime', 'publishTime', 'createdAt'])) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 通知通道 -->
      <el-tab-pane label="通道配置" name="channels">
        <div class="flex-between mb-12">
          <span class="muted">配置外部通知渠道（邮件 / 短信 / Webhook），供告警与审批通知使用</span>
          <el-button size="small" type="primary" :icon="Plus" @click="openChannel()">新增通道</el-button>
        </div>
        <el-table :data="channels" size="small" stripe border empty-text="暂无通知通道">
          <el-table-column label="通道名称" min-width="150">
            <template #default="{ row }">{{ pick(row, ['channelName', 'name'], '-') }}</template>
          </el-table-column>
          <el-table-column label="通道编码" width="140">
            <template #default="{ row }">{{ pick(row, ['channelCode'], '-') }}</template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ channelTypeText(pick(row, ['channelType', 'type'], '')) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="配置" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ configText(row) }}</template>
          </el-table-column>
          <el-table-column label="最近发送" width="200">
            <template #default="{ row }">
              <div class="muted">{{ fmtDateTime(pick(row, ['lastSendTime'])) }}</div>
              <div class="muted">{{ pick(row, ['lastSendStatus'], '') }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-switch :model-value="row.enabled !== false" @change="(v) => toggleChannel(row, v)" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openChannel(row)">编辑</el-button>
              <el-button link type="danger" @click="removeChannel(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 反馈 -->
      <el-tab-pane label="使用反馈" name="feedback">
        <el-form :model="feedbackForm" label-width="90px" style="max-width: 640px">
          <el-form-item label="资源类型">
            <el-select v-model="feedbackForm.resourceType" style="width: 100%">
              <el-option label="数据目录" value="CATALOG" />
              <el-option label="共享 API" value="API" />
              <el-option label="文件" value="FILE" />
              <el-option label="其他" value="OTHER" />
            </el-select>
          </el-form-item>
          <el-form-item label="资源ID">
            <el-input v-model="feedbackForm.resourceId" placeholder="可选，资源 ID" />
          </el-form-item>
          <el-form-item label="满意度">
            <el-rate v-model="feedbackForm.rating" :max="5" show-score />
          </el-form-item>
          <el-form-item label="反馈内容">
            <el-input v-model="feedbackForm.content" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="请描述您遇到的问题或建议" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submittingFeedback" @click="submitFeedback">提交反馈</el-button>
          </el-form-item>
        </el-form>
        <h4 class="section-title">反馈记录</h4>
        <el-table :data="feedbacks" size="small" stripe border empty-text="暂无反馈记录">
          <el-table-column label="资源类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ typeText(pick(row, ['resourceType'], '')) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['content', 'description', 'message'], '-') }}</template>
          </el-table-column>
          <el-table-column label="满意度" width="120" align="center">
            <template #default="{ row }">
              <el-rate :model-value="Number(pick(row, ['rating'], 0))" disabled />
            </template>
          </el-table-column>
          <el-table-column label="回复" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['reply'], '-') }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(row.status)">{{ feedbackStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['createTime', 'createdAt'])) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 公告发布 -->
    <el-dialog v-model="announcementVisible" title="发布公告" width="600px" append-to-body>
      <el-form ref="announcementRef" :model="announcementForm" :rules="announcementRules" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="announcementForm.title" maxlength="80" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input v-model="announcementForm.content" type="textarea" :rows="5" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="announcementVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingAnnouncement" @click="publishAnnouncement">发布</el-button>
      </template>
    </el-dialog>

    <!-- 通道编辑 -->
    <el-dialog v-model="channelVisible" :title="channelForm.id ? '编辑通知通道' : '新增通知通道'" width="560px" append-to-body>
      <el-form label-width="96px">
        <el-form-item label="通道名称">
          <el-input v-model="channelForm.channelName" placeholder="如：运维邮件通知" />
        </el-form-item>
        <el-form-item label="通道编码">
          <el-input v-model="channelForm.channelCode" placeholder="如：OPS_EMAIL" :disabled="!!channelForm.id" />
        </el-form-item>
        <el-form-item label="通道类型">
          <el-select v-model="channelForm.channelType" style="width: 100%">
            <el-option label="邮件 EMAIL" value="EMAIL" />
            <el-option label="短信 SMS" value="SMS" />
            <el-option label="站内 INBOX" value="INBOX" />
            <el-option label="Webhook" value="WEBHOOK" />
          </el-select>
        </el-form-item>
        <el-form-item label="配置参数">
          <el-input v-model="channelForm.configJson" type="textarea" :rows="5" placeholder='如 {"host":"smtp.example.com","port":465,"username":"notice@example.com","password":"***"}' />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="channelForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingChannel" @click="saveChannel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import request from '@/api/request'
import { confirmBox, fmtDateTime, listOf, pageOf, pick, tagType } from '@/api/helpers'

const loading = ref(false)
const tab = ref('messages')
const messages = ref([])
const messagePage = ref(1)
const messageSize = 10
const messageTotal = ref(0)
const unreadOnly = ref(false)
const unreadCount = ref(0)

const announcements = ref([])
const channels = ref([])
const feedbacks = ref([])

const announcementVisible = ref(false)
const savingAnnouncement = ref(false)
const announcementRef = ref()
const announcementForm = reactive({ title: '', content: '' })
const announcementRules = {
  title: [{ required: true, message: '请输入公告标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入公告内容', trigger: 'blur' }]
}

const channelVisible = ref(false)
const savingChannel = ref(false)
const channelForm = reactive({ id: null, channelCode: '', channelName: '', channelType: 'EMAIL', configJson: '', enabled: true })

const feedbackForm = reactive({ resourceType: 'CATALOG', resourceId: '', rating: 5, content: '' })
const submittingFeedback = ref(false)

const MSG_TYPE = { SYSTEM: '系统通知', APPROVAL: '审批通知', ALARM: '告警通知', EXCHANGE: '交换通知', ANNOUNCEMENT: '公告', TASK: '任务通知', CATALOG: '数据目录', API: '共享服务', FILE: '文件', OTHER: '其他' }
const CHANNEL_TYPE = { EMAIL: '邮件', SMS: '短信', WEBHOOK: 'Webhook', INBOX: '站内' }
const FEEDBACK_STATUS = { OPEN: '待处理', PENDING: '待处理', PROCESSING: '处理中', CLOSED: '已处理', RESOLVED: '已处理' }

function typeText(v) {
  const s = String(v || '').toUpperCase()
  return MSG_TYPE[s] || v || '-'
}

function channelTypeText(v) {
  return CHANNEL_TYPE[String(v || '').toUpperCase()] || v || '-'
}

function feedbackStatusText(v) {
  return FEEDBACK_STATUS[String(v || '').toUpperCase()] || v || '-'
}

function isRead(row) {
  if (row.readFlag !== undefined) return !!row.readFlag
  if (row.read !== undefined) return !!row.read
  return ['READ', '1', 'TRUE'].includes(String(row.status ?? '').toUpperCase())
}

function configText(row) {
  const c = row.configJson ?? row.config
  if (!c) return '-'
  return typeof c === 'string' ? c : JSON.stringify(c)
}

async function loadMessages() {
  try {
    const res = await request.get('/notify/v1/messages', {
      params: { pageNum: messagePage.value, pageSize: messageSize, unread: unreadOnly.value || undefined }
    })
    messages.value = listOf(res.data)
    messageTotal.value = pageOf(res).total
    if (unreadOnly.value) unreadCount.value = messageTotal.value
  } catch (e) {
    messages.value = []
    messageTotal.value = 0
  }
}

async function loadUnreadCount() {
  try {
    const res = await request.get('/notify/v1/messages', { params: { pageNum: 1, pageSize: 1, unread: true } })
    unreadCount.value = pageOf(res).total
  } catch (e) {
    unreadCount.value = 0
  }
}

async function markRead(row) {
  try {
    await request.post(`/notify/v1/messages/${row.id}/read`)
    ElMessage.success('已标记为已读')
    loadMessages()
    loadUnreadCount()
  } catch (e) {
    // 拦截器已提示
  }
}

async function readAll() {
  try {
    await request.post('/notify/v1/messages/read-all')
    ElMessage.success('全部消息已标记为已读')
    loadMessages()
    loadUnreadCount()
  } catch (e) {
    // 拦截器已提示
  }
}

async function loadAnnouncements() {
  try {
    const res = await request.get('/notify/v1/announcements', { params: { pageNum: 1, pageSize: 50 } })
    announcements.value = listOf(res.data)
  } catch (e) {
    announcements.value = []
  }
}

async function loadChannels() {
  try {
    const res = await request.get('/notify/v1/channels', { params: { pageNum: 1, pageSize: 100 } })
    channels.value = listOf(res.data)
  } catch (e) {
    channels.value = []
  }
}

async function loadFeedbacks() {
  try {
    const res = await request.get('/notify/v1/feedback', { params: { pageNum: 1, pageSize: 50 } })
    feedbacks.value = listOf(res.data)
  } catch (e) {
    feedbacks.value = []
  }
}

function reloadAll() {
  loading.value = true
  Promise.allSettled([loadMessages(), loadUnreadCount(), loadAnnouncements(), loadChannels(), loadFeedbacks()]).finally(() => {
    loading.value = false
  })
}

function onTab(name) {
  if (name === 'messages') {
    loadMessages()
    loadUnreadCount()
  }
  if (name === 'announcements') loadAnnouncements()
  if (name === 'channels') loadChannels()
  if (name === 'feedback') loadFeedbacks()
}

function openAnnouncement() {
  announcementForm.title = ''
  announcementForm.content = ''
  announcementVisible.value = true
}

async function publishAnnouncement() {
  try {
    await announcementRef.value.validate()
  } catch (e) {
    return
  }
  savingAnnouncement.value = true
  try {
    await request.post('/notify/v1/announcements', {
      title: announcementForm.title,
      content: announcementForm.content,
      level: 'NORMAL',
      status: 'PUBLISHED',
      topFlag: false
    })
    ElMessage.success('公告已发布')
    announcementVisible.value = false
    loadAnnouncements()
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingAnnouncement.value = false
  }
}

function openChannel(row) {
  if (row) {
    Object.assign(channelForm, {
      id: row.id,
      channelCode: pick(row, ['channelCode'], ''),
      channelName: pick(row, ['channelName', 'name'], ''),
      channelType: pick(row, ['channelType', 'type'], 'EMAIL'),
      configJson: typeof (row.configJson ?? row.config) === 'string' ? (row.configJson ?? row.config) : JSON.stringify(row.configJson ?? row.config ?? {}, null, 2),
      enabled: row.enabled !== false
    })
  } else {
    Object.assign(channelForm, { id: null, channelCode: '', channelName: '', channelType: 'EMAIL', configJson: '', enabled: true })
  }
  channelVisible.value = true
}

async function saveChannel() {
  if (!channelForm.channelName) {
    ElMessage.warning('请输入通道名称')
    return
  }
  if (!channelForm.channelCode) {
    ElMessage.warning('请输入通道编码')
    return
  }
  savingChannel.value = true
  try {
    const payload = {
      channelCode: channelForm.channelCode,
      channelName: channelForm.channelName,
      channelType: channelForm.channelType,
      configJson: channelForm.configJson,
      enabled: channelForm.enabled
    }
    if (channelForm.id) await request.put(`/notify/v1/channels/${channelForm.id}`, payload)
    else await request.post('/notify/v1/channels', payload)
    ElMessage.success('通知通道已保存')
    channelVisible.value = false
    loadChannels()
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingChannel.value = false
  }
}

async function toggleChannel(row, enabled) {
  try {
    await request.put(`/notify/v1/channels/${row.id}`, {
      channelCode: pick(row, ['channelCode'], ''),
      channelName: pick(row, ['channelName', 'name'], ''),
      channelType: pick(row, ['channelType'], 'EMAIL'),
      configJson: typeof (row.configJson ?? row.config) === 'string' ? row.configJson ?? row.config : JSON.stringify(row.configJson ?? row.config ?? {}),
      enabled
    })
    ElMessage.success(enabled ? '通道已启用' : '通道已停用')
    loadChannels()
  } catch (e) {
    // 拦截器已提示
  }
}

async function removeChannel(row) {
  const ok = await confirmBox('确认删除该通知通道？', '删除通道', 'error')
  if (!ok) return
  try {
    await request.delete(`/notify/v1/channels/${row.id}`)
    ElMessage.success('通道已删除')
    loadChannels()
  } catch (e) {
    // 拦截器已提示
  }
}

async function submitFeedback() {
  if (!feedbackForm.content) {
    ElMessage.warning('请填写反馈内容')
    return
  }
  submittingFeedback.value = true
  try {
    await request.post('/notify/v1/feedback', {
      resourceType: feedbackForm.resourceType,
      resourceId: feedbackForm.resourceId || undefined,
      rating: feedbackForm.rating,
      content: feedbackForm.content
    })
    ElMessage.success('反馈已提交，感谢您的建议')
    feedbackForm.content = ''
    loadFeedbacks()
  } catch (e) {
    // 拦截器已提示
  } finally {
    submittingFeedback.value = false
  }
}

onMounted(reloadAll)
</script>
