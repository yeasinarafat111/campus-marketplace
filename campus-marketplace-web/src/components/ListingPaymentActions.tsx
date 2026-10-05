import { Star } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'
import type { Listing } from '../api/listings'
import { useAuth } from '../hooks/useAuth'
import CheckoutButton from './CheckoutButton'
import Button from './ui/Button'
import { buttonClasses } from './ui/buttonClasses'

/**
 * The action buttons for a listing, by viewer: the owner can feature it, other students can reserve it,
 * logged-out visitors are asked to log in. Sold listings have no actions.
 */
export default function ListingPaymentActions({ listing, size = 'lg', block = false }: { listing: Listing; size?: 'md' | 'lg'; block?: boolean }) {
  const { user, isAuthenticated } = useAuth()
  const location = useLocation()
  const width = block ? 'w-full' : ''
  if (listing.status !== 'ACTIVE') return null

  if (user?.id === listing.owner.id) {
    return listing.featured ? null : (
      <CheckoutButton listingId={listing.id} purpose="FEATURE_LISTING" variant="accent" size={size} className={width}>
        <Star className="size-4" aria-hidden />
        Feature this listing
      </CheckoutButton>
    )
  }
  if (listing.reserved) {
    return (
      <Button variant="secondary" size={size} disabled className={width}>
        Reserved
      </Button>
    )
  }
  if (!isAuthenticated) {
    return (
      <Link to="/login" state={{ from: location }} className={buttonClasses({ size, className: width })}>
        Log in to reserve
      </Link>
    )
  }
  return (
    <CheckoutButton listingId={listing.id} purpose="RESERVE_ITEM" size={size} className={width}>
      Reserve this item
    </CheckoutButton>
  )
}
