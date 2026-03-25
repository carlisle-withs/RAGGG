import axios from 'axios'

const authApi = axios.create({
  baseURL: '/api/v1/auth',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

export const register = (data) => authApi.post('/register', data)
export const login = (data) => authApi.post('/login', data)
export const refreshToken = (data) => authApi.post('/refresh', data)

export default authApi
