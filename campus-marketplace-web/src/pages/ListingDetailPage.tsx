import { Link, useParams } from 'react-router-dom'
import { getListing } from '../api/listings'
import StatePanel from '../components/StatePanel'
import NotFoundPage from './NotFoundPage'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import ListingBadges from '../components/ListingBadges'
import ListingPaymentActions from '../components/ListingPaymentActions'
import { useAuth } from '../hooks/useAuth'
import { useFetch } from '../hooks/useFetch'
import { formatPrice } from '../lib'

export default function ListingDetailPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const { data: listing, error, errorStatus, loading } = useFetch(() => getListing(id!), [id])

  useDocumentTitle(listing?.title ?? 'Listing')

  if (loading) return <p className="text-muted">Loading…</p>
  if (errorStatus === 404) return <NotFoundPage what="listing" />
  if (error) {
    return (
      <StatePanel title="This listing couldn't be loaded" actions={<Link to="/listings" className="btn btn-primary">Back to the board</Link>}>
        {error}
      </StatePanel>
    )
  }
  if (!listing) return null

  const isOwner = user?.id === listing.owner.id

  return (
    <div>
      <Link to="/listings" className="link text-sm">
        Back to the board
      </Link>
      <div className={`mt-4 grid gap-8 md:grid-cols-2 ${listing.featured ? 'border-t-4 border-gold pt-6' : 'border-t-2 border-navy pt-6'}`}>
        {listing.imageUrl ? (
          <img src={listing.imageUrl} alt={listing.title} className="w-full border border-rule object-cover" />
        ) : (
          <div className="flex h-72 items-center justify-center border border-dashed border-rule text-muted">No photo</div>
        )}
        <div>
          <ListingBadges listing={listing} />
          <h1 className="mt-3 text-4xl leading-tight sm:text-5xl">{listing.title}</h1>
          <p className="mt-2 font-display text-5xl font-bold text-navy">{formatPrice(listing.price)}</p>

          {listing.description && (
            <p className="mt-5 whitespace-pre-line border-t border-rule pt-5 text-lg leading-relaxed">{listing.description}</p>
          )}

          <dl className="mt-5 grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 border-t border-rule pt-5 text-sm">
            <dt className="text-muted">Seller</dt>
            <dd className="font-semibold">{listing.owner.fullName}</dd>
            <dt className="text-muted">Listed</dt>
            <dd>{new Date(listing.createdAt).toLocaleDateString()}</dd>
          </dl>

          <div className="mt-6 flex flex-wrap items-start gap-3 border-t border-rule pt-5">
            <ListingPaymentActions listing={listing} />
            {isOwner && (
              <Link to={`/listings/${listing.id}/edit`} className="btn btn-outline">
                Edit listing
              </Link>
            )}
          </div>
          {listing.status === 'ACTIVE' && listing.reserved && !isOwner && (
            <p className="mt-3 text-sm text-muted">Another student has already paid a deposit to hold this item.</p>
          )}
        </div>
      </div>
    </div>
  )
}
