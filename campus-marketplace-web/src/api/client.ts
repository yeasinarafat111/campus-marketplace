import axios, { type InternalAxiosRequestConfig } from 'axios'
import { getAccessToken, getRefreshToken, setAccessToken, setRefreshToken } from './tokens'

const baseURL = import.meta.env.VITE_API_URL

const client = axios.create({
  baseURL,
  headers: { 'Content-Type': 'application/json' },
})

client.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Registered by AuthProvider: invoked when a 401 can't be recovered by refreshing the token.
let onSessionExpired: (() => void) | null = null
export const setSessionExpiredHandler = (handler: (() => void) | null) => {
  onSessionExpired = handler
}

// A single in-flight refresh is shared by all requests that hit a 401 at the same time.
let refreshing: Promise<string> | null = null

export function refreshAccessToken(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return Promise.reject(new Error('No refresh token'))
  refreshing ??= axios
    .post<{ accessToken: string }>(`${baseURL}/api/auth/refresh`, { refreshToken })
    .then((res) => {
      setAccessToken(res.data.accessToken)
      return res.data.accessToken
    })
    .catch((err) => {
      setAccessToken(null)
      setRefreshToken(null)
      throw err
    })
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

type RetryableConfig = InternalAxiosRequestConfig & { _retried?: boolean }

client.interceptors.response.use(undefined, async (error) => {
  const original: RetryableConfig | undefined = error.config
  const isAuthCall = original?.url?.startsWith('/api/auth/')
  if (error.response?.status === 401 && original && !original._retried && !isAuthCall) {
    original._retried = true
    try {
      const token = await refreshAccessToken()
      original.headers.Authorization = `Bearer ${token}`
      return client(original)
    } catch {
      // Refresh failed (refreshAccessToken already cleared the tokens): end the session.
      onSessionExpired?.()
    }
  }
  return Promise.reject(error)
})

export default client
