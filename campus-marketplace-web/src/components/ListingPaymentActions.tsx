import { useQuery } from '@tanstack/react-query'
import { Star } from 'lucide-react'
import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import type { Listing } from '../api/listings'
import { getFeeQuote } from '../api/payments'
import { useAuth } from '../hooks/useAuth'
import { formatCents } from '../lib'
import CheckoutButton from './CheckoutButton'
import ReserveDialog from './ReserveDialog'
import Button from './ui/Button'
import { buttonClasses } from './ui/buttonClasses'

/** Decision D7: only paid listings (at least $1.00) have fees, so only they can be featured or reserved. */
const isChargeable = (listing: Listing) => listing.price >= 1

/**
 * The action buttons for a listing, by viewer: the owner can feature it, other students can reserve it,
 * logged-out visitors are asked to log in. Sold listings have no actions. Each paid button shows the
 * server-quoted fee, with the rule under it.
 */
export default function ListingPaymentActions({ listing, size = 'lg', block = false }: { listing: Listing; size?: 'md' | 'lg'; block?: boolean }) {
  const { user, isAuthenticated } = useAuth()
  const location = useLocation()
  const [reserveOpen, setReserveOpen] = useState(false)
  const isOwner = user?.id === listing.owner.id
  const needsQuote = isAuthenticated && listing.status === 'ACTIVE' && isChargeable(listing) && !(isOwner ? listing.featured : listing.reserved)
  const quote = useQuery({
    queryKey: ['fees', listing.id, listing.price],
    queryFn: () => getFeeQuote(listing.id),
    enabled: needsQuote,
  })
  const width = block ? 'w-full' : ''
  if (listing.status !== 'ACTIVE') return null

  if (isOwner) {
    if (listing.featured || !isChargeable(listing)) return null
    return (
      <div className={width}>
        <CheckoutButton listingId={listing.id} purpose="FEATURE_LISTING" variant="accent" size={size} className={width} disabled={!quote.data}>
          <Star className="size-4" aria-hidden />
          {quote.data ? `Feature for ${quote.data.featureDays} days: ${formatCents(quote.data.featureCents)}` : 'Feature this listing'}
        </CheckoutButton>
        {quote.data && <FeeNote quote={quote.data} />}
      </div>
    )
  }
  if (listing.reserved) {
    return (
      <Button variant="secondary" size={size} disabled className={width}>
        Reserved
      </Button>
    )
  }
  if (!isChargeable(listing)) {
    return listing.price === 0 ? <p className="text-sm text-muted">This item is free, so it can't be reserved.</p> : null
  }
  if (!isAuthenticated) {
    return (
      <Link to="/login" state={{ from: location }} className={buttonClasses({ size, className: width })}>
        Log in to reserve
      </Link>
    )
  }
  return (
    <div className={width}>
      <Button size={size} className={width} disabled={!quote.data} onClick={() => setReserveOpen(true)}>
        {quote.data ? `Reserve: ${formatCents(quote.data.reserveCents)} deposit` : 'Reserve this item'}
      </Button>
      {quote.data && <FeeNote quote={quote.data} />}
      {quote.data && (
        <ReserveDialog open={reserveOpen} onOpenChange={setReserveOpen} listingId={listing.id} quote={quote.data} />
      )}
    </div>
  )
}

/** "(3% of price, min $0.50)": how the fee above was worked out. */
function FeeNote({ quote }: { quote: { rate: number; minimumCents: number } }) {
  const percent = Number((quote.rate * 100).toFixed(2))
  return (
    <p className="mt-1.5 text-center text-xs text-muted">
      ({percent}% of price, min {formatCents(quote.minimumCents)})
    </p>
  )
}
