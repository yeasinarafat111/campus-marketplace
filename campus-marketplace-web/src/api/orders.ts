import client from './client'
import type { Contact, MeetupSpot, Page } from './listings'

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
  meetupSpot: MeetupSpot | null
  /** CASH: the buyer paid the balance to the seller in person, so the platform only held the deposit. */
  paymentMethod: 'ONLINE' | 'CASH'
  status: 'PAID' | 'COMPLETED' | 'CANCELLED'
  payoutStatus: 'PENDING' | 'PAID_OUT'
  createdAt: string
  completedAt: string | null
  /** When a PAID order completes by itself; null once it isn't waiting any more or while a report freezes it. */
  autoCompleteAt: string | null
  problemReportedAt: string | null
  /** The buyer's own text: only in the buyer's view. */
  problemReason: string | null
  /** The other side of the order: the seller on a purchase, the buyer on a sale. */
  counterparty: Contact
  /** Buyer's view: the order is complete and not rated yet. */
  canRate: boolean
  /** The buyer's rating of the seller; both sides see it. */
  rating: { stars: number; comment: string | null; createdAt: string } | null
}

export type OrderKind = 'purchases' | 'sales'

export const getOrders = (kind: OrderKind, params: { page?: number; size?: number }) =>
  client.get<Page<Order>>(`/api/orders/${kind}`, { params }).then((r) => r.data)

/** Buyer: "I received it". Completes the order. */
export const confirmReceipt = (id: number) => client.post<Order>(`/api/orders/${id}/confirm`).then((r) => r.data)

/** Buyer: report a problem. Freezes the order until an admin resolves it. */
export const reportProblem = (id: number, reason: string) =>
  client.post<Order>(`/api/orders/${id}/report`, { reason }).then((r) => r.data)

/** Buyer: rate the seller 1-5 once the order is complete. One rating per order. */
export const rateOrder = (id: number, stars: number, comment: string) =>
  client.post<Order>(`/api/orders/${id}/rating`, { stars, comment: comment.trim() || null }).then((r) => r.data)
