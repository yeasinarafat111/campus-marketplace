import { Link } from 'react-router-dom'
import { CONDITION_LABELS, type Listing } from '../api/listings'
import { cx, formatCategory, timeAgo } from '../lib'
import ListingBadges from './ListingBadges'
import ImageWithFallback from './ui/ImageWithFallback'
import PriceTag from './ui/PriceTag'
import SaveButton from './SaveButton'

/** Photo first: a square image, then the price, a one-line title and muted meta text. */
export default function ListingCard({ listing, className }: { listing: Listing; className?: string }) {
  return (
    <div className={cx('relative min-w-0', className)}>
      <Link to={`/listings/${listing.id}`} className="group block">
        <div className="relative">
          <ImageWithFallback
            src={listing.imageUrl}
            alt={listing.title}
            category={listing.category}
            className="aspect-square w-full rounded-xl transition-opacity duration-150 group-hover:opacity-90"
          />
          <div className="absolute left-2 top-2 flex flex-wrap gap-1">
            <ListingBadges listing={listing} />
          </div>
        </div>
        <PriceTag price={listing.price} size="md" className="mt-2 block" />
        <h3 className="truncate text-sm text-ink group-hover:underline">{listing.title}</h3>
        <p className="truncate text-xs text-muted">
          {formatCategory(listing.category)}{listing.itemCondition ? ` · ${CONDITION_LABELS[listing.itemCondition]}` : ''} · {timeAgo(listing.createdAt)}
        </p>
      </Link>
      <SaveButton listingId={listing.id} className="absolute right-2 top-2" />
    </div>
  )
}
