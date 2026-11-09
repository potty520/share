import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000
})

// 同一错误短时间内只提示一次，避免页面并发加载时刷屏
let lastError = ''
let lastErrorTime = 0
function showError(message) {
  const msg = message || '请求失败'
  const now = Date.now()
  if (msg === lastError && now - lastErrorTime < 2000) return
  lastError = msg
  lastErrorTime = now
  ElMessage.error(msg)
}

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('share_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data
    // 文件流直接返回
    if (body instanceof Blob) {
      return body
    }
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body
      }
      showError(body.message || '请求失败')
      if (body.code === 40100) {
        localStorage.removeItem('share_token')
        router.push('/login')
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      localStorage.removeItem('share_token')
      router.push('/login')
      showError('登录已过期，请重新登录')
    } else {
      showError(error.response?.data?.message || error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

export default request
