import client from './client'
import type { Contact, Page } from './listings'

export interface Order {
  id: number
  /** Null once the listing has been deleted. */
  listingId: number | null
  listingTitle: string
  listingImageUrl: string | null
  /** All money is in minor units (cents). */
  itemPriceCents: number
  depositCents: number
  balanceCents: number
  currency: string
  status: 'PAID' | 'COMPLETED' | 'CANCELLED'
  payoutStatus: 'PENDING' | 'PAID_OUT'
  createdAt: string
  completedAt: string | null
  /** The other side of the order: the seller on a purchase, the buyer on a sale. */
  counterparty: Contact
}

export type OrderKind = 'purchases' | 'sales'

export const getOrders = (kind: OrderKind, params: { page?: number; size?: number }) =>
  client.get<Page<Order>>(`/api/orders/${kind}`, { params }).then((r) => r.data)
