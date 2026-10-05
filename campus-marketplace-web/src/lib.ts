export const formatPrice = (price: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(price)

export const formatCategory = (c: string) => c.charAt(0) + c.slice(1).toLowerCase()

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
