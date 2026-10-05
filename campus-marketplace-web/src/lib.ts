export const formatPrice = (price: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(price)

export const formatCents = (cents: number) => formatPrice(cents / 100)

export const formatCategory = (c: string) => c.charAt(0) + c.slice(1).toLowerCase()

/** "just now", "5m ago", "2h ago", "3d ago", then a short date. */
export function timeAgo(iso: string, now = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}d ago`
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

// The backend's cancel URL can't carry a listing id, so what is being paid for is remembered here before
// leaving for Stripe and read back on the /payments/* pages.
const CHECKOUT_KEY = 'campus.checkout'

export type CheckoutPurpose = 'FEATURE_LISTING' | 'RESERVE_ITEM' | 'PURCHASE'

export const rememberCheckout = (listingId: number, purpose: CheckoutPurpose) => {
  try {
    sessionStorage.setItem(CHECKOUT_KEY, JSON.stringify({ listingId, purpose }))
  } catch {
    // Storage can be unavailable (private mode); the pages fall back to the listings index.
  }
}

export const recallCheckout = (): { listingId: number; purpose: CheckoutPurpose } | null => {
  try {
    const raw = sessionStorage.getItem(CHECKOUT_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

/** "1d 4h", "3h 20m", "12m", or "less than a minute"; "expired" once the time has passed. */
export function formatRemaining(iso: string, now = Date.now()): string {
  const minutes = Math.floor((new Date(iso).getTime() - now) / 60_000)
  if (minutes < 0) return 'expired'
  if (minutes < 1) return 'less than a minute'
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${minutes % 60}m`
  return `${minutes}m`
}

export const formatDateTime = (iso: string) =>
  new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })

/** Same rule as the backend's @BangladeshiPhone: 01XXXXXXXXX, 8801… or +8801…, operator digit 3-9. */
export const isValidBdPhone = (raw: string) => /^(\+?880|0)1[3-9]\d{8}$/.test(raw.replace(/[\s-]/g, ''))

export const PHONE_HINT = '01XXXXXXXXX'
export const PHONE_ERROR = 'Enter a valid Bangladeshi mobile number, like 01XXXXXXXXX'

export const formatDate = (iso: string) =>
  new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' })

export const formatMonthYear = (iso: string) =>
  new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'long' })

/** Joins class names, skipping falsy values. */
export const cx = (...parts: (string | false | null | undefined)[]) => parts.filter(Boolean).join(' ')

/** "Ada Lovelace" -> "AL". */
export const initials = (name: string) => {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  const first = parts[0][0]
  const last = parts.length > 1 ? parts[parts.length - 1][0] : ''
  return (first + last).toUpperCase()
}

export const LINK_CLASS = 'font-semibold text-navy underline-offset-2 hover:underline'
