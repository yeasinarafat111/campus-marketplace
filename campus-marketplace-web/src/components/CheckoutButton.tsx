import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { parseApiError } from '../api/errors'
import { createCheckoutSession, type PaymentPurpose } from '../api/payments'
import { rememberCheckout } from '../lib'
import Button, { type ButtonProps } from './ui/Button'

interface Props extends Omit<ButtonProps, 'onClick' | 'loading'> {
  listingId: number
  purpose: PaymentPurpose
}

/** Creates a Stripe Checkout session for the listing and sends the browser to Stripe. */
export default function CheckoutButton({ listingId, purpose, children, ...buttonProps }: Props) {
  const [loading, setLoading] = useState(false)

  // Coming back with the browser's Back button restores this page from cache with the button still busy.
  useEffect(() => {
    const reset = (e: PageTransitionEvent) => e.persisted && setLoading(false)
    window.addEventListener('pageshow', reset)
    return () => window.removeEventListener('pageshow', reset)
  }, [])

  async function onClick() {
    setLoading(true)
    try {
      const session = await createCheckoutSession({ listingId, purpose })
      if (!session.url.startsWith('https://')) throw new Error('Unexpected checkout URL')
      rememberCheckout(listingId, purpose)
      window.location.href = session.url // leave the button busy while the browser navigates
    } catch (e) {
      toast.error(parseApiError(e).message)
      setLoading(false)
    }
  }

  return (
    <Button onClick={onClick} loading={loading} {...buttonProps}>
      {loading ? 'Redirecting to Stripe…' : children}
    </Button>
  )
}
