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
  paymentMethod: 'ONLINE' | 'CASH'
  /** The whole price for an online sale, only the deposit for a cash one. */
  owedToSellerCents: number
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

export const REPORT_REASONS = ['SCAM', 'PROHIBITED_ITEM', 'WRONG_CATEGORY', 'OTHER'] as const
export type ReportReason = (typeof REPORT_REASONS)[number]
export const REPORT_REASON_LABELS: Record<ReportReason, string> = {
  SCAM: 'Looks like a scam',
  PROHIBITED_ITEM: 'Prohibited item',
  WRONG_CATEGORY: 'Wrong category',
  OTHER: 'Something else',
}

export type ReportAction = 'RESOLVE' | 'DELETE_LISTING' | 'DEACTIVATE_USER'

export interface AdminReport {
  id: number
  reason: ReportReason
  details: string | null
  status: 'OPEN' | 'RESOLVED'
  resolution: ReportAction | null
  createdAt: string
  resolvedAt: string | null
  listingTitle: string
  /** Null once the listing is deleted. */
  listing: { id: number; status: string } | null
  reporter: { id: number; fullName: string; active: boolean }
  reportedUser: { id: number; fullName: string; active: boolean } | null
}

export const listReports = (params: { status?: 'OPEN' | 'RESOLVED'; page?: number; size?: number }) =>
  client.get<Page<AdminReport>>('/api/admin/reports', { params }).then((r) => r.data)

export const resolveReport = (id: number, action: ReportAction) =>
  client.post<AdminReport>(`/api/admin/reports/${id}/resolve`, { action }).then((r) => r.data)
