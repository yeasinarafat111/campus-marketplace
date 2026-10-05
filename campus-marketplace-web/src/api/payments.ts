import client from './client'

export type PaymentPurpose = 'FEATURE_LISTING' | 'RESERVE_ITEM' | 'PURCHASE' | 'POSTING_FEE'

export interface CheckoutSession {
  paymentId: number
  sessionId: string
  /** Stripe-hosted checkout page; the browser should be sent here. */
  url: string
  /** Smallest currency unit (cents for USD). */
  amount: number
  currency: string
  purpose: PaymentPurpose
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'REFUNDED'
}

export const createCheckoutSession = (body: { listingId: number; purpose: PaymentPurpose }) =>
  client.post<CheckoutSession>('/api/payments/checkout-session', body).then((r) => r.data)

/** Fees for a listing, computed by the server. The client only displays them. */
export interface FeeQuote {
  featureCents: number
  reserveCents: number
  /** Balance at Buy: price minus the deposit. Only meaningful for the current reserver. */
  purchaseCents: number
  currency: string
  /** Nominal rate, e.g. 0.03. The minimum may have raised the actual fee. */
  rate: number
  minimumCents: number
  /** The listing price the fee was computed from, e.g. "12.00". */
  basis: string
  featureDays: number
  reservationHours: number
}

export const getFeeQuote = (listingId: number) =>
  client.get<FeeQuote>(`/api/listings/${listingId}/fees`).then((r) => r.data)
