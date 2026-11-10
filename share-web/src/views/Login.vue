<template>
  <div class="login-page">
    <span class="login-decoration dec-a"></span>
    <span class="login-decoration dec-b"></span>

    <div class="login-card">
      <section class="login-brand">
        <div class="brand-head">
          <span class="brand-badge">SHARE EXCHANGE</span>
          <h1>共享交换系统</h1>
        </div>

        <img class="login-hero" :src="heroImage" alt="数据共享交换示意图" />

        <ul class="brand-points">
          <li>数据目录一网通查，共享申请在线审批</li>
          <li>任务调度与质量检核，数据流转全程审计</li>
          <li>分类分级与动态脱敏，敏感数据安全可控</li>
        </ul>
      </section>

      <section class="login-form">
        <h2>账号登录</h2>
        <p class="login-hint">请输入统一身份账号，并填写右侧图形验证码</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" size="large" placeholder="请输入用户名" clearable>
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" size="large" show-password placeholder="请输入密码">
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="图形验证码" prop="captchaCode">
            <div class="captcha-row">
              <el-input
                v-model="form.captchaCode"
                size="large"
                placeholder="请输入验证码"
                maxlength="6"
                clearable
              >
                <template #prefix>
                  <el-icon><PictureFilled /></el-icon>
                </template>
              </el-input>
              <button
                type="button"
                class="captcha-box"
                :class="{ 'is-loading': captchaLoading }"
                title="看不清？点击刷新"
                @click="refreshCaptcha"
              >
                <img v-if="captchaImage" :src="captchaImage" alt="图形验证码" />
                <span v-else class="captcha-placeholder">{{ captchaLoading ? '加载中…' : '点击获取' }}</span>
              </button>
            </div>
            <div class="captcha-tip">
              <span>看不清？<a @click.prevent="refreshCaptcha">换一张</a></span>
              <span v-if="captchaExpires">验证码 {{ captchaExpires }} 秒内有效</span>
            </div>
          </el-form-item>

          <el-button type="primary" size="large" class="login-submit" :loading="loading" @click="submit">
            登 录
          </el-button>
        </el-form>
      </section>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import heroImage from '@/assets/login-hero.svg'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref()
const loading = ref(false)
const captchaLoading = ref(false)
const captchaImage = ref('')
const captchaId = ref('')
const captchaExpires = ref(0)

const form = reactive({ username: '', password: '', captchaCode: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }]
}

async function refreshCaptcha() {
  if (captchaLoading.value) return
  captchaLoading.value = true
  try {
    const data = await auth.captcha()
    captchaId.value = data?.captchaId || ''
    captchaImage.value = data?.image || ''
    captchaExpires.value = data?.expiresIn || 0
    form.captchaCode = ''
  } catch (e) {
    captchaImage.value = ''
    captchaId.value = ''
  } finally {
    captchaLoading.value = false
  }
}

async function submit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  if (!captchaId.value) {
    ElMessage.warning('验证码未加载，请点击验证码图片刷新')
    refreshCaptcha()
    return
  }
  loading.value = true
  try {
    await auth.login({
      username: form.username,
      password: form.password,
      captchaId: captchaId.value,
      captchaCode: form.captchaCode
    })
    ElMessage.success('登录成功')
    try {
      await auth.fetchProfile()
    } catch (e) {
      // profile 失败不影响登录，权限已在登录响应中
    }
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/dashboard')
  } catch (e) {
    // 错误提示由 request 拦截器统一处理；验证码已作废，刷新一张
    refreshCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(refreshCaptcha)
</script>
