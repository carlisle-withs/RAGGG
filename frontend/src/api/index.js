import axios from 'axios'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 聊天
export const chat = (data) => api.post('/chat', data)

// 上传文档
export const uploadDocument = (formData) => api.post('/documents/upload', formData, {
  headers: { 'Content-Type': 'multipart/form-data' }
})

// 获取文档状态
export const getDocumentStatus = (id) => api.get(`/documents/${id}/status`)

// 检索
export const retrieve = (data) => api.post('/retrieve', data)

export default api
