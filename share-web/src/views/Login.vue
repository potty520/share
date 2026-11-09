<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div>
          <h1>共享交换系统</h1>
          <p>政务数据共享交换统一门户 · 目录编目 · 交换任务 · 共享服务 · 安全管控 · 运维监控</p>
          <ul class="brand-points">
            <li>◈ 数据目录一网通查，共享申请在线审批</li>
            <li>◈ 任务调度与质量检核，数据流转全程审计</li>
            <li>◈ 分类分级与动态脱敏，敏感数据安全可控</li>
          </ul>
        </div>
        <div class="muted" style="color: rgba(255, 255, 255, 0.65); font-size: 12px">
          © {{ year }} 市大数据局 · 共享交换平台 V1.0
        </div>
      </div>

      <div class="login-form">
        <h2>账号登录</h2>
        <p class="muted" style="margin: 0 0 18px">请使用统一身份账号登录，admin / security 角色需短信验证码</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" size="large" placeholder="请输入用户名" clearable>
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" size="large" show-password placeholder="请输入密码">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item prop="smsCode">
            <template #label>
              <span>短信验证码 <span class="muted">（admin / security 必填）</span></span>
            </template>
            <div class="flex-gap" style="width: 100%">
              <el-input
                v-model="form.smsCode"
                size="large"
                placeholder="演示环境点击右侧获取"
                maxlength="6"
                style="flex: 1"
              />
              <el-button size="large" :disabled="countdown > 0" :loading="sending" @click="getSmsCode">
                {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>

          <el-button type="primary" size="large" style="width: 100%; margin-top: 4px" :loading="loading" @click="submit">
            登 录
          </el-button>
        </el-form>

        <div class="mt-12">
          <span class="muted">演示账号（密码均为 123456，点击快速填充）：</span>
          <div class="demo-accounts">
            <div v-for="acc in demoAccounts" :key="acc.username" class="demo-account" @click="fill(acc)">
              <span>{{ acc.username }}</span>
              <span>{{ acc.role }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref()
const loading = ref(false)
const sending = ref(false)
const countdown = ref(0)
const year = new Date().getFullYear()
let timer = null

const form = reactive({ username: '', password: '', smsCode: '' })

const demoAccounts = [
  { username: 'admin', role: '系统管理员' },
  { username: 'provider', role: '数据提供方' },
  { username: 'consumer', role: '数据使用方' },
  { username: 'security', role: '安全管理员' },
  { username: 'ops', role: '交换运维' },
  { username: 'bizadmin', role: '业务管理员' }
]

const needsSms = computed(() => ['admin', 'security'].includes(form.username))

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  smsCode: [
    {
      validator: (rule, value, callback) => {
        if (needsSms.value && !value) {
          callback(new Error('该角色需要短信验证码'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

function fill(acc) {
  form.username = acc.username
  form.password = '123456'
  form.smsCode = ''
  ElMessage.info(`已填充演示账号：${acc.username}（${acc.role}）`)
}

async function getSmsCode() {
  if (!form.username) {
    ElMessage.warning('请先输入用户名')
    return
  }
  sending.value = true
  try {
    const data = await auth.smsCode(form.username)
    let code = ''
    if (typeof data === 'string') code = data
    else if (data) code = data.code || data.smsCode || data.captcha || ''
    if (code) {
      form.smsCode = String(code)
      ElMessage.success(`演示环境验证码：${code}（已自动填入）`)
    } else {
      ElMessage.success('验证码已发送，请查看短信')
    }
    countdown.value = 60
    clearInterval(timer)
    timer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e) {
    // 拦截器已提示错误（接口未就绪等），此处保持安静
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  loading.value = true
  try {
    await auth.login({ username: form.username, password: form.password, smsCode: form.smsCode || undefined })
    ElMessage.success('登录成功')
    try {
      await auth.fetchProfile()
    } catch (e) {
      // profile 失败不影响登录，权限已在登录响应中
    }
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/dashboard')
  } catch (e) {
    // 错误提示由 request 拦截器统一处理
  } finally {
    loading.value = false
  }
}

onBeforeUnmount(() => clearInterval(timer))
</script>
