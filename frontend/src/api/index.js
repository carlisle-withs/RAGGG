import axios from 'axios'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 300000,  // 5分钟超时，大文件上传需要更长的时间
  headers: {
    'Content-Type': 'application/json'
  }
})

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
  timeout: 600000  // 批量上传需要更长时间
})

// 获取文档列表
export const getDocuments = () => api.get('/documents')

// 获取文档状态
export const getDocumentStatus = (id) => api.get(`/documents/${id}/status`)

// 删除文档
export const deleteDocument = (id) => api.delete(`/documents/${id}`)

// 检索
export const retrieve = (data) => api.post('/retrieve', data)

export default api
