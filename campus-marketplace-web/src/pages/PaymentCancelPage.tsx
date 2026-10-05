import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { Link } from 'react-router-dom'
import { recallCheckoutListing } from '../lib'

export default function PaymentCancelPage() {
  useDocumentTitle('Payment cancelled')
  const listingId = recallCheckoutListing()
  return (
    <div className="mx-auto max-w-xl border-t-4 border-gold pt-6">
      <h1 className="text-4xl">Payment cancelled</h1>
      <p className="mt-3 text-lg">You weren't charged. Nothing about the listing has changed.</p>
      <div className="mt-6 flex flex-wrap gap-3">
        <Link to={listingId ? `/listings/${listingId}` : '/listings'} className="btn btn-primary">
          {listingId ? 'Back to the listing' : 'Back to browsing'}
        </Link>
      </div>
    </div>
  )
}
