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

// The backend's cancel URL can't carry a listing id, so the listing being paid for is remembered
// here before leaving for Stripe and read back on the /payments/* pages.
const CHECKOUT_LISTING_KEY = 'campus.checkoutListingId'

export const rememberCheckoutListing = (id: number) => {
  try {
    sessionStorage.setItem(CHECKOUT_LISTING_KEY, String(id))
  } catch {
    // Storage can be unavailable (private mode); the pages fall back to the listings index.
  }
}

export const recallCheckoutListing = (): string | null => {
  try {
    return sessionStorage.getItem(CHECKOUT_LISTING_KEY)
  } catch {
    return null
  }
}

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
