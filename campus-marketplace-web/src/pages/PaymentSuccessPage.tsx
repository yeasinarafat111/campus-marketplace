import { useQueryClient } from '@tanstack/react-query'
import { CircleCheck, Loader2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { type Listing } from '../api/listings'
import DemoNotice from '../components/DemoNotice'
import StatePanel from '../components/StatePanel'
import { buttonClasses } from '../components/ui/buttonClasses'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useListing } from '../hooks/useListing'
import { formatDateTime, recallCheckout, type CheckoutPurpose } from '../lib'

const POLL_MS = 1500
const TIMEOUT_MS = 15_000

/** Stripe confirms to us in the background, so ask the listing until it shows the effect of the payment. */
const applied = (purpose: CheckoutPurpose, l: Listing) =>
  purpose === 'FEATURE_LISTING' ? l.featured : purpose === 'RESERVE_ITEM' ? l.reservedByMe : purpose === 'POSTING_FEE' ? l.status === 'ACTIVE' : l.status === 'SOLD'

function message(purpose: CheckoutPurpose, l: Listing): string {
  if (purpose === 'FEATURE_LISTING') {
    return l.featuredUntil ? `Featured until ${formatDateTime(l.featuredUntil)}.` : 'Your listing is now featured.'
  }
  if (purpose === 'POSTING_FEE') return 'Your listing is live. Students can find it now.'
  if (purpose === 'RESERVE_ITEM') {
    return l.reservationExpiresAt ? `Reserved for you until ${formatDateTime(l.reservationExpiresAt)}.` : 'The item is reserved for you.'
  }
  return 'Purchase complete. The seller has been told.'
}

export default function PaymentSuccessPage() {
  useDocumentTitle('Payment received')
  const [checkout] = useState(recallCheckout)
  const queryClient = useQueryClient()
  const [timedOut, setTimedOut] = useState(false)

  const listing = useListing(checkout?.listingId, {
    staleTime: 0,
    // Keep asking until the webhook has applied the payment, then stop.
    refetchInterval: (data) => (data && checkout && applied(checkout.purpose, data) ? false : POLL_MS),
  })
  const done = Boolean(checkout && listing.data && applied(checkout.purpose, listing.data))

  useEffect(() => {
    if (done || !checkout) return
    const id = setTimeout(() => setTimedOut(true), TIMEOUT_MS)
    return () => clearTimeout(id)
  }, [done, checkout])

  useEffect(() => {
    if (!done) return
    // The new state is visible everywhere else too.
    for (const key of ['listings', 'my-listings', 'orders', 'fees']) queryClient.invalidateQueries({ queryKey: [key] })
  }, [done, queryClient])

  const actions = (
    <>
      {checkout && <Link to={`/listings/${checkout.listingId}`} className={buttonClasses()}>View the listing</Link>}
      {done && checkout?.purpose === 'PURCHASE' && <Link to="/purchases" className={buttonClasses({ variant: 'secondary' })}>My purchases</Link>}
      <Link to="/listings" className={buttonClasses({ variant: 'secondary' })}>Back to browsing</Link>
    </>
  )

  if (done && checkout && listing.data) {
    return <StatePanel note={<DemoNotice />} icon={CircleCheck} title="All done" actions={actions}>{message(checkout.purpose, listing.data)}</StatePanel>
  }
  if (timedOut || !checkout) {
    return (
      <StatePanel note={<DemoNotice />} icon={CircleCheck} title="Payment received" actions={actions}>
        Payment received; it may take a minute to show.
      </StatePanel>
    )
  }
  return (
    <StatePanel note={<DemoNotice />} icon={Loader2} title="Confirming your payment" actions={actions}>
      <span role="status">This only takes a moment. Please don&apos;t close the page.</span>
    </StatePanel>
  )
}
