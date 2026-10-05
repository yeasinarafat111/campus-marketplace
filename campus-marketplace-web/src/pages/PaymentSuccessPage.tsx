import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { Link } from 'react-router-dom'
import { recallCheckoutListing } from '../lib'

export default function PaymentSuccessPage() {
  useDocumentTitle('Payment received')
  const listingId = recallCheckoutListing()
  return (
    <div className="mx-auto max-w-xl border-t-4 border-sage pt-6">
      <h1 className="text-4xl">Payment received</h1>
      <p className="mt-3 text-lg">Thank you. Your listing will update shortly.</p>
      <p className="mt-2 text-muted">
        Stripe confirms payments to us in the background, so the Featured or Reserved tag can take a few moments to
        appear. If it hasn't after a minute, reload the listing page.
      </p>
      <div className="mt-6 flex flex-wrap gap-3">
        {listingId && (
          <Link to={`/listings/${listingId}`} className="btn btn-primary">
            View the listing
          </Link>
        )}
        <Link to="/listings" className="btn btn-outline">
          Back to browsing
        </Link>
      </div>
    </div>
  )
}
