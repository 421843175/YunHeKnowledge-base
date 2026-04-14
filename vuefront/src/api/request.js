import axios from 'axios'

const request = axios.create({
  baseURL: 'http://127.0.0.1:8090/api/v1',
  timeout: 15000,
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('ai_enterprise_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export default request

