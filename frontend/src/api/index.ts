import axios from 'axios'
import type { ApiResponse } from '@/types'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

// 请求拦截器
request.interceptors.request.use(
  config => {
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器：统一解开 { code, message, data } 包装，code !== 200 转为异常
request.interceptors.response.use(
  response => {
    const res = response.data as ApiResponse
    if (res.code !== 200) {
      console.error('API Error:', res.message)
      return Promise.reject(new Error(res.message || 'Error'))
    }
    // 拦截器把 AxiosResponse 换成 ApiResponse，调用方以 res.data 取业务数据
    return res as unknown as typeof response
  },
  // HTTP 非 2xx（404/409/500 等）：后端响应体仍是 { code, message }，取出业务消息供 err.message 展示
  error => {
    const message = error?.response?.data?.message
    console.error('Request Error:', message || error.message)
    return Promise.reject(new Error(message || error.message))
  }
)

export default request

// 统一出口：页面从 '@/api' 导入各业务 API
export { alertApi } from './alert'
export { incidentApi } from './incident'
export { statsApi } from './stats'
export type { EvaluationQuery, DrillQuery } from './stats'
