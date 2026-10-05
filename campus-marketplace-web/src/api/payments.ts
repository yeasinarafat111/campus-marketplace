import client from './client'

export type PaymentPurpose = 'FEATURE_LISTING' | 'RESERVE_ITEM'

export interface CheckoutSession {
  paymentId: number
  sessionId: string
  /** Stripe-hosted checkout page; the browser should be sent here. */
  url: string
  /** Smallest currency unit (cents for USD). */
  amount: number
  currency: string
  purpose: PaymentPurpose
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED'
}

export const createCheckoutSession = (body: { listingId: number; purpose: PaymentPurpose }) =>
  client.post<CheckoutSession>('/api/payments/checkout-session', body).then((r) => r.data)
