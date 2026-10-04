import { Link } from 'react-router-dom'
import type { Listing } from '../api/listings'
import { formatCategory, formatPrice } from '../lib'

export default function ListingCard({ listing }: { listing: Listing }) {
  return (
    <Link
      to={`/listings/${listing.id}`}
      className="overflow-hidden rounded-xl bg-white shadow-sm ring-1 ring-gray-200 transition hover:shadow-md"
    >
      {listing.imageUrl ? (
        <img src={listing.imageUrl} alt="" className="h-44 w-full object-cover" loading="lazy" />
      ) : (
        <div className="flex h-44 items-center justify-center bg-gray-100 text-sm text-gray-400">No image</div>
      )}
      <div className="p-4">
        <div className="flex items-start justify-between gap-2">
          <h3 className="line-clamp-2 font-semibold text-gray-900">{listing.title}</h3>
          <span className="shrink-0 font-semibold text-indigo-600">{formatPrice(listing.price)}</span>
        </div>
        <p className="mt-1 text-xs text-gray-500">
          {formatCategory(listing.category)} · {listing.owner.fullName}
        </p>
      </div>
    </Link>
  )
}
