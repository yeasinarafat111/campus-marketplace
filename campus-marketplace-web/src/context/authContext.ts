import { createContext } from 'react'
import type { AuthResponse } from '../api/auth'
import type { User } from '../api/users'

export interface AuthContextValue {
  user: User | null
  accessToken: string | null
  isAuthenticated: boolean
  /** True until the bootstrap check (refresh token -> access token -> user) has finished. */
  isLoading: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => void
  /** Replaces the cached profile, e.g. with the response of a profile edit. */
  setUser: (user: User) => void
  /** Swaps in a fresh token pair, e.g. after a password change (which ends every other session). */
  applyTokens: (tokens: AuthResponse) => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
