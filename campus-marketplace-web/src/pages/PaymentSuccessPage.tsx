import { CircleCheck } from 'lucide-react'
import { Link } from 'react-router-dom'
import StatePanel from '../components/StatePanel'
import { buttonClasses } from '../components/ui/buttonClasses'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { recallCheckoutListing } from '../lib'

export default function PaymentSuccessPage() {
  useDocumentTitle('Payment received')
  const listingId = recallCheckoutListing()
  return (
    <StatePanel
      icon={CircleCheck}
      title="Payment received"
      actions={
        <>
          {listingId && (
            <Link to={`/listings/${listingId}`} className={buttonClasses()}>
              View the listing
            </Link>
          )}
          <Link to="/listings" className={buttonClasses({ variant: 'secondary' })}>
            Back to browsing
          </Link>
        </>
      }
    >
      Thank you. Stripe confirms payments to us in the background, so the Featured or Reserved badge can take a few moments to appear. If it hasn't after a minute, reload the listing page.
    </StatePanel>
  )
}
