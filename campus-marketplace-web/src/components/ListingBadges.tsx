import type { Listing } from '../api/listings'
import { formatCategory } from '../lib'

/** Flat tags: category always, plus status flags. Featured is a paid placement so it gets the gold treatment. */
export default function ListingBadges({ listing }: { listing: Listing }) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {listing.featured && <span className="tag tag-featured">Featured</span>}
      {listing.status === 'SOLD' && <span className="tag tag-sold">Sold</span>}
      {listing.reserved && <span className="tag tag-reserved">Reserved</span>}
      <span className="tag">{formatCategory(listing.category)}</span>
    </div>
  )
}
