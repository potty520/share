/**
 * 前端通用辅助：时间格式化、分页/响应解包、状态标签、确认框、下载等。
 * 所有接口均返回 { code, message, traceId, data, page }，request.js 已解包拦截器只做校验，
 * 因此页面里统一用 res.data / res.page。
 */
import { ElMessage, ElMessageBox } from 'element-plus'

/** 日期时间：兼容 ISO 字符串、时间戳、数组形式 */
export function fmtDateTime(v) {
  if (v === null || v === undefined || v === '') return '-'
  let d = v
  if (Array.isArray(v)) {
    const [y, m, day, h = 0, mi = 0, s = 0] = v
    d = new Date(y, (m || 1) - 1, day || 1, h, mi, s)
  } else if (typeof v === 'number') {
    d = new Date(v)
  } else if (typeof v === 'string') {
    const str = v.replace('T', ' ').replace(/\.\d+/, '')
    if (/^\d{4}-\d{2}-\d{2}/.test(str)) {
      return str.length > 19 ? str.slice(0, 19) : str
    }
    d = new Date(v)
  }
  if (!(d instanceof Date) || Number.isNaN(d.getTime())) return String(v)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function fmtDate(v) {
  const s = fmtDateTime(v)
  return s === '-' ? '-' : s.slice(0, 10)
}

export function fmtNumber(v) {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return n.toLocaleString('zh-CN')
}

export function fmtPercent(v, digits = 2) {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  const val = n <= 1 ? n * 100 : n
  return `${val.toFixed(digits)}%`
}

/** 去掉空值后的查询参数 */
export function clean(params = {}) {
  const out = {}
  Object.keys(params).forEach((k) => {
    const v = params[k]
    if (v === '' || v === null || v === undefined) return
    out[k] = typeof v === 'string' ? v.trim() : v
  })
  return out
}

/** 分页元信息 */
export function pageOf(res) {
  const p = res?.page || res?.data?.page || {}
  return {
    total: Number(p.total ?? res?.total ?? 0) || 0,
    pageNum: Number(p.pageNum ?? 1) || 1,
    pageSize: Number(p.pageSize ?? 10) || 10
  }
}

/** 从 data 中取数组：兼容 list/items/records/content/rows 等命名 */
export function listOf(data) {
  if (Array.isArray(data)) return data
  if (!data || typeof data !== 'object') return []
  for (const k of ['list', 'items', 'records', 'content', 'rows', 'data', 'children']) {
    if (Array.isArray(data[k])) return data[k]
  }
  return []
}

/** 取第一个有值的字段 */
export function pick(obj, keys, dft = '') {
  if (!obj) return dft
  for (const k of keys) {
    const v = obj[k]
    if (v !== undefined && v !== null && v !== '') return v
  }
  return dft
}

export function numOf(v) {
  const n = Number(v)
  return Number.isNaN(n) ? 0 : n
}

const TAG_TYPE = {
  SUCCESS: 'success',
  SUCCEED: 'success',
  ENABLED: 'success',
  ONLINE: 'success',
  PUBLISHED: 'success',
  PASSED: 'success',
  APPROVED: 'success',
  ACTIVE: 'success',
  NORMAL: 'success',
  DONE: 'success',
  FINISHED: 'success',
  COMPLETED: 'success',
  DRAFT: 'info',
  DISABLED: 'info',
  DELETED: 'info',
  ARCHIVED: 'info',
  PENDING: 'warning',
  RUNNING: 'warning',
  WAITING: 'warning',
  RETRY: 'warning',
  WARN: 'warning',
  WARNING: 'warning',
  PROCESSING: 'warning',
  PARTIAL: 'warning',
  FAILED: 'danger',
  FAIL: 'danger',
  ERROR: 'danger',
  REJECTED: 'danger',
  OFFLINE: 'danger',
  STOPPED: 'danger',
  REVOKED: 'danger',
  EXPIRED: 'danger',
  TIMEOUT: 'danger'
}

export function tagType(status) {
  if (status === null || status === undefined) return 'info'
  return TAG_TYPE[String(status).trim().toUpperCase()] || 'info'
}

/** 确认框：返回 true/false，不抛异常 */
export async function confirmBox(message, title = '操作确认', type = 'warning') {
  try {
    await ElMessageBox.confirm(message, title, { type, confirmButtonText: '确定', cancelButtonText: '取消' })
    return true
  } catch (e) {
    return false
  }
}

export function notifyOk(msg = '操作成功') {
  ElMessage.success(msg)
}

/** 保存 Blob 到本地文件 */
export function saveBlob(blob, filename) {
  const url = window.URL.createObjectURL(blob instanceof Blob ? blob : new Blob([blob]))
  const a = document.createElement('a')
  a.href = url
  a.download = filename || 'download'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

/** 从 Content-Disposition 解析文件名 */
export function filenameFrom(headers, dft) {
  const cd = headers?.['content-disposition'] || ''
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd)
  if (m && m[1]) {
    try {
      return decodeURIComponent(m[1])
    } catch (e) {
      return m[1]
    }
  }
  return dft || 'download'
}

/** 表单校验失败时统一提示 */
export function invalidForm() {
  ElMessage.warning('请先完善表单必填项')
}

/** 图表容器自适应：返回清理函数 */
export function autoResize(chart) {
  if (!chart) return () => {}
  const handler = () => {
    try {
      chart.resize()
    } catch (e) {
      /* 忽略 */
    }
  }
  window.addEventListener('resize', handler)
  return () => window.removeEventListener('resize', handler)
}

/** 通用趋势数据归一化：把各种后端结构转成 {labels:[], values:[]} */
export function toSeries(data, valueKeys = ['value', 'count', 'amount', 'volume', 'total', 'qps']) {
  const rows = listOf(data)
  const labels = []
  const values = []
  rows.forEach((row) => {
    if (row === null || row === undefined) return
    if (typeof row === 'number') {
      labels.push(String(labels.length + 1))
      values.push(row)
      return
    }
    const label = pick(row, ['time', 'date', 'label', 'hour', 'day', 'period', 'name', 'statTime', 'dt'], '')
    const value = pick(row, valueKeys, 0)
    labels.push(String(label))
    values.push(numOf(value))
  })
  return { labels, values }
}

/** 把统计对象安全转成指标卡数据 */
export function statValue(obj, keys, dft = '0') {
  const v = pick(obj, keys, dft)
  return v === '' || v === null || v === undefined ? dft : v
}
