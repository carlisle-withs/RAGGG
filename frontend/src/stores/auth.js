import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, register as apiRegister, refreshToken as apiRefreshToken } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || null)
  const refreshTokenValue = ref(localStorage.getItem('refreshToken') || null)
  const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))

  const isAuthenticated = computed(() => !!token.value)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  function setAuth(authData) {
    token.value = authData.token
    refreshTokenValue.value = authData.refreshToken
    user.value = authData.user

    localStorage.setItem('token', authData.token)
    localStorage.setItem('refreshToken', authData.refreshToken)
    localStorage.setItem('user', JSON.stringify(authData.user))
  }

  function clearAuth() {
    token.value = null
    refreshTokenValue.value = null
    user.value = null

    localStorage.removeItem('token')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('user')
  }

  async function login(username, password) {
    const response = await apiLogin({ username, password })
    setAuth(response.data)
    return response.data
  }

  async function register(username, password, email) {
    const response = await apiRegister({ username, password, email })
    setAuth(response.data)
    return response.data
  }

  async function refreshToken() {
    if (!refreshTokenValue.value) {
      throw new Error('No refresh token')
    }
    const response = await apiRefreshToken({ refreshToken: refreshTokenValue.value })
    setAuth(response.data)
    return response.data
  }

  function logout() {
    clearAuth()
  }

  return {
    token,
    refreshTokenValue,
    user,
    isAuthenticated,
    isAdmin,
    login,
    register,
    refreshToken,
    logout,
    setAuth,
    clearAuth
  }
})
