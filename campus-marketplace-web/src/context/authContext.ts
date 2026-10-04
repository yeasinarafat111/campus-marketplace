import { createContext } from 'react'
import type { User } from '../api/users'

export interface AuthContextValue {
  user: User | null
  accessToken: string | null
  isAuthenticated: boolean
  /** True until the bootstrap check (refresh token -> access token -> user) has finished. */
  isLoading: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
