import { defineStore } from 'pinia'
import request from '@/api/request'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('share_token') || '',
    user: JSON.parse(localStorage.getItem('share_user') || 'null'),
    permissions: JSON.parse(localStorage.getItem('share_perms') || '[]'),
    menus: JSON.parse(localStorage.getItem('share_menus') || '[]')
  }),
  getters: {
    isLogin: (s) => !!s.token,
    roleCode: (s) => s.user?.roleCode || '',
    isAdmin: (s) => s.user?.roleCode === 'ADMIN',
    hasPerm: (s) => (perm) => {
      if (!perm) return true
      if (s.user?.roleCode === 'ADMIN') return true
      return s.permissions.includes(perm)
    }
  },
  actions: {
    async login(form) {
      const res = await request.post('/auth/v1/login', form)
      this.applyAuth(res.data)
      return res.data
    },
    async smsCode(username) {
      const res = await request.post('/auth/v1/sms-code', { username })
      return res.data
    },
    applyAuth(data) {
      this.token = data.accessToken
      this.user = data.user
      this.permissions = data.permissions || []
      localStorage.setItem('share_token', this.token)
      localStorage.setItem('share_user', JSON.stringify(this.user))
      localStorage.setItem('share_perms', JSON.stringify(this.permissions))
    },
    async fetchProfile() {
      const res = await request.get('/auth/v1/profile')
      this.user = res.data.user || res.data
      this.permissions = res.data.permissions || []
      this.menus = res.data.menus || []
      localStorage.setItem('share_user', JSON.stringify(this.user))
      localStorage.setItem('share_perms', JSON.stringify(this.permissions))
      localStorage.setItem('share_menus', JSON.stringify(this.menus))
      return res.data
    },
    async logout() {
      try {
        await request.post('/auth/v1/logout')
      } catch (e) {
        // 忽略退出失败
      }
      this.token = ''
      this.user = null
      this.permissions = []
      this.menus = []
      localStorage.removeItem('share_token')
      localStorage.removeItem('share_user')
      localStorage.removeItem('share_perms')
      localStorage.removeItem('share_menus')
    }
  }
})
