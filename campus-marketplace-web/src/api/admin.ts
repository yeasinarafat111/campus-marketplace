import client from './client'
import type { Page } from './listings'
import type { Role } from './users'

export interface AdminUser {
  id: number
  fullName: string
  email: string
  role: Role
  enabled: boolean
  active: boolean
  createdAt: string
}

export const listUsers = (params: { page?: number; size?: number }) =>
  client.get<Page<AdminUser>>('/api/admin/users', { params }).then((r) => r.data)

export const deactivateUser = (id: number) => client.patch<AdminUser>(`/api/admin/users/${id}/deactivate`)
export const reactivateUser = (id: number) => client.patch<AdminUser>(`/api/admin/users/${id}/reactivate`)

export const adminDeleteListing = (id: number) => client.delete(`/api/admin/listings/${id}`)
