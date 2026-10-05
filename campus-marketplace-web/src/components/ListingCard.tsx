import { Link } from 'react-router-dom'
import type { Listing } from '../api/listings'
import { formatPrice } from '../lib'
import ListingBadges from './ListingBadges'

/** One cell of the listings board. Cells are separated by rules (the grid supplies the outer border). */
export default function ListingCard({ listing }: { listing: Listing }) {
  return (
    <Link
      to={`/listings/${listing.id}`}
      className={`group flex flex-col border-b border-r border-rule p-4 hover:bg-white ${
        listing.featured ? 'border-t-4 border-t-gold bg-gold/10' : ''
      }`}
    >
      {listing.imageUrl ? (
        <img src={listing.imageUrl} alt="" className="aspect-[4/3] w-full border border-rule object-cover" loading="lazy" />
      ) : (
        <div className="flex aspect-[4/3] w-full items-center justify-center border border-dashed border-rule text-sm text-muted">
          No photo
        </div>
      )}
      <p className="mt-3 font-display text-3xl font-bold leading-none text-navy">{formatPrice(listing.price)}</p>
      <h3 className="mt-1 line-clamp-2 font-sans text-base font-semibold text-ink group-hover:underline">
        {listing.title}
      </h3>
      <div className="mt-2">
        <ListingBadges listing={listing} />
      </div>
      <p className="mt-auto pt-3 text-sm text-muted">Sold by {listing.owner.fullName}</p>
    </Link>
  )
}
