import { Star } from 'lucide-react'
import type { Listing } from '../api/listings'
import Badge from './ui/Badge'

/**
 * Status flags for a listing. Featured is the paid placement (gold). Reserved is amber, Sold is gray, and
 * "Available" (green) is only shown when `showAvailable` is set, e.g. on the detail page.
 */
export default function ListingBadges({ listing, showAvailable = false }: { listing: Listing; showAvailable?: boolean }) {
  const available = listing.status === 'ACTIVE' && !listing.reserved
  return (
    <>
      {listing.featured && listing.status === 'ACTIVE' && (
        <Badge tone="featured">
          <Star className="size-3 fill-current" aria-hidden />
          Featured
        </Badge>
      )}
      {listing.status === 'SOLD' && <Badge tone="muted">Sold</Badge>}
      {listing.status === 'ACTIVE' && listing.reserved && <Badge tone="warning">Reserved</Badge>}
      {showAvailable && available && <Badge tone="success">Available</Badge>}
    </>
  )
}
