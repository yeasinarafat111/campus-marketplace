import client from './client'
import type { AuthResponse } from './auth'

export type Role = 'STUDENT' | 'ADMIN'

/** The caller's own profile (GET /api/users/me). Phone is null for accounts created before phones existed. */
export interface User {
  id: number
  fullName: string
  email: string
  phone: string | null
  role: Role
  createdAt: string
}

export const getMe = () => client.get<User>('/api/users/me')

/** Omitted fields are left unchanged. */
export const updateProfile = (body: { fullName?: string; phone?: string }) =>
  client.patch<User>('/api/users/me', body)

/** Ends every other session; the returned tokens keep this one logged in. */
export const changePassword = (body: { currentPassword: string; newPassword: string }) =>
  client.post<AuthResponse>('/api/users/me/password', body)
