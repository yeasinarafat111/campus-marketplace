import client from './client'
import type { Page } from './listings'

export const LEDGER_TYPES = ['POSTING_FEE', 'FEATURE_FEE', 'FORFEITED_DEPOSIT'] as const
export type LedgerType = (typeof LEDGER_TYPES)[number]
export type GroupBy = 'day' | 'week' | 'month'

export const LEDGER_LABELS: Record<LedgerType, string> = {
  POSTING_FEE: 'Posting fees',
  FEATURE_FEE: 'Feature fees',
  FORFEITED_DEPOSIT: 'Forfeited deposits',
}

/** Money is in cents. Revenue is before Stripe's processing fees. */
export interface RevenueReport {
  from: string
  to: string
  groupBy: GroupBy
  currency: string
  totals: Record<LedgerType, number>
  netCents: number
  series: { bucket: string; byType: Record<LedgerType, number>; netCents: number }[]
  /** Purchase money held for sellers not yet paid out (a balance as of now). Not revenue. */
  owedToSellersCents: number
  /** Deposits of reservations still running (as of now). Not revenue unless forfeited. */
  depositsHeldCents: number
}

export interface LedgerEntry {
  id: number
  type: LedgerType
  amountCents: number
  currency: string
  occurredAt: string
  paymentId: number | null
  listing: { id: number | null; title: string | null } | null
  user: { id: number; fullName: string; email: string } | null
}

export const getRevenue = (params: { from: string; to: string; groupBy: GroupBy }) =>
  client.get<RevenueReport>('/api/admin/revenue', { params }).then((r) => r.data)

export const getRevenueEntries = (params: { from: string; to: string; type?: LedgerType; page?: number; size?: number }) =>
  client.get<Page<LedgerEntry>>('/api/admin/revenue/entries', { params }).then((r) => r.data)

/** The CSV for the same filters, fetched with the login and saved as a file. */
export async function downloadRevenueCsv(params: { from: string; to: string; type?: LedgerType }) {
  const { data } = await client.get<Blob>('/api/admin/revenue/entries/export', { params, responseType: 'blob' })
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = `revenue-${params.from}-to-${params.to}.csv`
  link.click()
  URL.revokeObjectURL(url)
}
