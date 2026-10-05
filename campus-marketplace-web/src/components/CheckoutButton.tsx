import { useEffect, useState } from 'react'
import { parseApiError } from '../api/errors'
import { createCheckoutSession, type PaymentPurpose } from '../api/payments'
import { rememberCheckoutListing } from '../lib'

interface Props {
  listingId: number
  purpose: PaymentPurpose
  className: string
  children: React.ReactNode
}

/** Creates a Stripe Checkout session for the listing and sends the browser to Stripe. */
export default function CheckoutButton({ listingId, purpose, className, children }: Props) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  // Coming back with the browser's Back button restores this page from cache with the button still busy.
  useEffect(() => {
    const reset = (e: PageTransitionEvent) => e.persisted && setLoading(false)
    window.addEventListener('pageshow', reset)
    return () => window.removeEventListener('pageshow', reset)
  }, [])

  async function onClick() {
    setError('')
    setLoading(true)
    try {
      const session = await createCheckoutSession({ listingId, purpose })
      if (!session.url.startsWith('https://')) throw new Error('Unexpected checkout URL')
      rememberCheckoutListing(listingId)
      window.location.href = session.url // leave the button busy while the browser navigates
    } catch (e) {
      setError(parseApiError(e).message)
      setLoading(false)
    }
  }

  return (
    <div>
      <button type="button" onClick={onClick} disabled={loading} className={className}>
        {loading ? 'Redirecting to Stripe…' : children}
      </button>
      {error && (
        <p role="alert" className="mt-2 border-l-4 border-danger bg-white px-3 py-2 text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  )
}
