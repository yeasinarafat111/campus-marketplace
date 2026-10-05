import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { login as loginRequest, type AuthResponse } from '../api/auth'
import { refreshAccessToken, setSessionExpiredHandler } from '../api/client'
import { getRefreshToken, setAccessToken, setRefreshToken } from '../api/tokens'
import { getMe, type User } from '../api/users'
import { AuthContext } from './authContext'

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const [user, setUser] = useState<User | null>(null)
  const [accessToken, setToken] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(() => getRefreshToken() !== null)

  const clearSession = useCallback(() => {
    setAccessToken(null)
    setRefreshToken(null)
    setToken(null)
    setUser(null)
  }, [])

  // Bootstrap: restore the session after a reload by exchanging the stored refresh token,
  // then loading the user. Any failure just leaves the visitor logged out.
  useEffect(() => {
    if (!getRefreshToken()) return
    let cancelled = false
    refreshAccessToken()
      .then(async (token) => {
        const { data } = await getMe()
        if (cancelled) return
        setToken(token)
        setUser(data)
      })
      .catch(() => {
        if (!cancelled) clearSession()
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [clearSession])

  // If a request 401s and the refresh also fails, drop the session and go to /login.
  useEffect(() => {
    setSessionExpiredHandler(() => {
      clearSession()
      navigate('/login', { replace: true })
    })
    return () => setSessionExpiredHandler(null)
  }, [clearSession, navigate])

  const login = useCallback(
    async (email: string, password: string) => {
      const { data } = await loginRequest({ email, password })
      setAccessToken(data.accessToken)
      try {
        const me = await getMe()
        setRefreshToken(data.refreshToken)
        setToken(data.accessToken)
        setUser(me.data)
      } catch (err) {
        clearSession()
        throw err
      }
    },
    [clearSession],
  )

  const logout = useCallback(() => {
    clearSession()
    navigate('/login', { replace: true })
  }, [clearSession, navigate])

  const applyTokens = useCallback((tokens: AuthResponse) => {
    setAccessToken(tokens.accessToken)
    setRefreshToken(tokens.refreshToken)
    setToken(tokens.accessToken)
  }, [])

  const value = useMemo(
    () => ({
      user,
      accessToken,
      isAuthenticated: user !== null,
      isLoading,
      login,
      logout,
      setUser,
      applyTokens,
    }),
    [user, accessToken, isLoading, login, logout, applyTokens],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
