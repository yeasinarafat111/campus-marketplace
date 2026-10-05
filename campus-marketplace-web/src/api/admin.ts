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

export interface AdminOrder {
  id: number
  listingId: number | null
  listingTitle: string
  itemPriceCents: number
  depositCents: number
  balanceCents: number
  currency: string
  meetupSpot: string | null
  status: 'PAID' | 'COMPLETED' | 'CANCELLED'
  payoutStatus: 'PENDING' | 'PAID_OUT'
  createdAt: string
  completedAt: string | null
  problemReportedAt: string | null
  problemReason: string | null
  buyer: { id: number; fullName: string; email: string; phone: string | null }
  seller: { id: number; fullName: string; email: string; phone: string | null }
}

/** `reported: true` is the problem queue (open reports, oldest first). */
export const listOrders = (params: { reported?: boolean; page?: number; size?: number }) =>
  client.get<Page<AdminOrder>>('/api/admin/orders', { params }).then((r) => r.data)

/** Only for COMPLETED orders. */
export const markPaidOut = (id: number) => client.patch<AdminOrder>(`/api/admin/orders/${id}/payout`).then((r) => r.data)

export const resolveProblem = (id: number, outcome: 'COMPLETE' | 'CANCEL') =>
  client.patch<AdminOrder>(`/api/admin/orders/${id}/resolve`, null, { params: { outcome } }).then((r) => r.data)
