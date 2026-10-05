import type { Listing } from '../api/listings'
import { useAuth } from '../hooks/useAuth'
import CheckoutButton from './CheckoutButton'

/** Feature (owner only) and Reserve (other signed-in users only) buttons for an active listing. */
export default function ListingPaymentActions({ listing }: { listing: Listing }) {
  const { user } = useAuth()
  if (!user || listing.status !== 'ACTIVE') return null

  if (user.id === listing.owner.id) {
    return listing.featured ? null : (
      <CheckoutButton listingId={listing.id} purpose="FEATURE_LISTING" className="btn btn-gold">
        Feature this listing
      </CheckoutButton>
    )
  }
  return listing.reserved ? null : (
    <CheckoutButton listingId={listing.id} purpose="RESERVE_ITEM" className="btn btn-sage">
      Reserve this item
    </CheckoutButton>
  )
}
