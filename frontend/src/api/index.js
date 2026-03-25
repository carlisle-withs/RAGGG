import axios from 'axios'
import { useAuthStore } from '../stores/auth'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 300000,
  headers: {
    'Content-Type': 'application/json'
  }
})

api.interceptors.request.use(
  (config) => {
    const authStore = useAuthStore()
    if (authStore.token) {
      config.headers.Authorization = `Bearer ${authStore.token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config

    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true

      try {
        const authStore = useAuthStore()
        await authStore.refreshToken()
        originalRequest.headers.Authorization = `Bearer ${authStore.token}`
        return api(originalRequest)
      } catch (refreshError) {
        const authStore = useAuthStore()
        authStore.logout()
        window.location.href = '/login'
        return Promise.reject(refreshError)
      }
    }

    return Promise.reject(error)
  }
)

// 聊天
export const chat = (data) => api.post('/chat', data)

// 上传文档
export const uploadDocument = (formData) => api.post('/documents/upload', formData, {
  headers: { 'Content-Type': 'multipart/form-data' },
  timeout: 300000
})

// 批量上传文档
export const uploadDocumentsBatch = (formData) => api.post('/documents/upload/batch', formData, {
  headers: { 'Content-Type': 'multipart/form-data' },
  timeout: 600000
})

// 获取文档列表
export const getDocuments = () => api.get('/documents')

// 获取文档状态
export const getDocumentStatus = (id) => api.get(`/documents/${id}/status`)

// 删除文档
export const deleteDocument = (id) => api.delete(`/documents/${id}`)

// 检索
export const retrieve = (data) => api.post('/retrieve', data)

// 知识库管理
export const getKnowledgeBases = () => api.get('/kbs')
export const createKnowledgeBase = (data) => api.post('/kbs', data)
export const updateKnowledgeBase = (id, data) => api.put(`/kbs/${id}`, data)
export const deleteKnowledgeBase = (id) => api.delete(`/kbs/${id}`)

export default api
