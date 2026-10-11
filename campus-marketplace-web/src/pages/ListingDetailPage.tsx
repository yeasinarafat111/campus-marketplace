import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, ShieldCheck, TriangleAlert } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { CONDITION_LABELS, MEETUP_LABELS, getSellerProfile, type Listing } from '../api/listings'
import { Stars } from '../components/StarRating'
import { parseApiError } from '../api/errors'
import AskAiButton from '../components/AskAiButton'
import ListingBadges from '../components/ListingBadges'
import ListingPaymentActions from '../components/ListingPaymentActions'
import MessageSellerButton from '../components/MessageSellerButton'
import Avatar from '../components/ui/Avatar'
import { buttonClasses } from '../components/ui/buttonClasses'
import { PhotoGallery } from '../components/PhotoGallery'
import ReportListingButton from '../components/ReportListingButton'
import SaveButton from '../components/SaveButton'
import PriceTag from '../components/ui/PriceTag'
import Skeleton from '../components/ui/Skeleton'
import StatePanel from '../components/StatePanel'
import { useAuth } from '../hooks/useAuth'
import { useListing } from '../hooks/useListing'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { formatCategory, formatMonthYear, timeAgo } from '../lib'
import NotFoundPage from './NotFoundPage'
import { isAxiosError } from 'axios'

export default function ListingDetailPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const { data: listing, error, isPending } = useListing(id)

  useDocumentTitle(listing?.title ?? 'Listing')

  if (isPending) return <DetailSkeleton />
  if (isAxiosError(error) && error.response?.status === 404) return <NotFoundPage what="listing" />
  if (error || !listing) {
    return (
      <StatePanel
        icon={TriangleAlert}
        title="This listing couldn't be loaded"
        actions={<Link to="/listings" className={buttonClasses()}>Back to browsing</Link>}
      >
        {parseApiError(error).message}
      </StatePanel>
    )
  }

  const isOwner = user?.id === listing.owner.id
  const managingReservation = listing.status === 'ACTIVE' && (listing.reservedByMe || (isOwner && listing.reserved))

  return (
    // Room under the content for the fixed bar (two rows plus the iPhone home indicator), so it never covers anything.
    <div className={managingReservation ? '' : 'pb-44 lg:pb-0'}>
      <Link to="/listings" className="mb-4 inline-flex items-center gap-1 text-sm font-medium text-muted transition-colors hover:text-navy">
        <ArrowLeft className="size-4" aria-hidden />
        Back to browsing
      </Link>

      <div className="grid gap-x-10 gap-y-6 lg:grid-cols-[minmax(0,1fr)_380px]">
        <PhotoGallery
          photos={listing.images.length > 0 ? listing.images : listing.imageUrl ? [{ id: 0, url: listing.imageUrl, position: 0 }] : []}
          title={listing.title}
          category={listing.category}
        />

        <aside className="lg:row-span-2 lg:self-start">
          <div className="space-y-5 lg:sticky lg:top-24">
            <div className="rounded-xl border border-line bg-white p-5 shadow-sm">
              <div className="flex flex-wrap gap-1.5">
                <ListingBadges listing={listing} showAvailable />
              </div>
              <h1 className="mt-3 text-2xl font-bold leading-tight tracking-tight text-ink">{listing.title}</h1>
              <PriceTag price={listing.price} size="lg" className="mt-2 block" />
              <p className="mt-1 text-sm text-muted">
                {formatCategory(listing.category)} · Listed {timeAgo(listing.createdAt)}
              </p>
              <div className="mt-3 flex flex-wrap items-center gap-2">
                {!isOwner && <SaveButton listingId={listing.id} withLabel className="border border-line" />}
                <AskAiButton listing={listing} />
              </div>

              <div className={`mt-5 flex-col gap-2 ${managingReservation ? 'flex' : 'hidden lg:flex'}`}>
                <ListingActions listing={listing} isOwner={isOwner} block />
              </div>
              {listing.status === 'ACTIVE' && listing.reserved && !isOwner && !listing.reservedByMe && (
                <p className="mt-3 text-sm text-muted">Another student has already paid a deposit to hold this item.</p>
              )}
            </div>

            <SellerCard listing={listing} />
            <SafetyTips />
            {!isOwner && <ReportListingButton listingId={listing.id} />}
          </div>
        </aside>

        <section aria-labelledby="description-heading">
          <h2 id="description-heading" className="text-lg font-bold text-navy">Description</h2>
          {listing.description ? (
            <p className="mt-2 whitespace-pre-line leading-relaxed text-ink">{listing.description}</p>
          ) : (
            <p className="mt-2 text-muted">The seller didn't add a description.</p>
          )}
          <dl className="mt-5 grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5 border-t border-line pt-4 text-sm">
            <dt className="text-muted">Category</dt>
            <dd>{formatCategory(listing.category)}</dd>
            {listing.itemCondition && (
              <>
                <dt className="text-muted">Condition</dt>
                <dd>{CONDITION_LABELS[listing.itemCondition]}</dd>
              </>
            )}
            {listing.meetupSpot && (
              <>
                <dt className="text-muted">Meetup spot</dt>
                <dd>{MEETUP_LABELS[listing.meetupSpot]}</dd>
              </>
            )}
          </dl>
        </section>
      </div>

      {/* The phone's action bar is for the simple cases. When you hold or manage a reservation there are too many
          choices for a bar (pay online or cash, release, cancel, confirm), so the full panel shows instead.
          Two rows: price and the secondary action, then the primary button across the full width with its fee
          hint underneath. */}
      {!managingReservation && (
        <div data-action-bar className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-white px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] lg:hidden [&_a]:whitespace-nowrap [&_button]:whitespace-nowrap">
          <div className="flex min-h-11 items-center justify-between gap-3">
            <PriceTag price={listing.price} size="lg" />
            {listing.status === 'SOLD' ? (
              <span className="text-sm font-semibold text-muted">Sold</span>
            ) : isOwner ? (
              listing.status === 'ACTIVE' && (
                <Link to={`/listings/${listing.id}/edit`} className={buttonClasses({ variant: 'secondary', className: 'h-11 px-5' })}>Edit</Link>
              )
            ) : (
              <MessageSellerButton listing={listing} size="md" className="h-11" />
            )}
          </div>
          {listing.status !== 'SOLD' && (
            <div className="mt-2">
              <ListingPaymentActions listing={listing} block compact />
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function ListingActions({ listing, isOwner, block = false }: { listing: Listing; isOwner: boolean; block?: boolean }) {
  return (
    <>
      <ListingPaymentActions listing={listing} block={block} />
      <MessageSellerButton listing={listing} block={block} size="lg" />
      {isOwner && listing.status === 'ACTIVE' && (
        <Link to={`/listings/${listing.id}/edit`} className={buttonClasses({ variant: 'secondary', size: 'lg', className: block ? 'w-full' : '' })}>
          Edit
        </Link>
      )}
      {listing.status === 'SOLD' && <SoldNote listing={listing} isOwner={isOwner} />}
    </>
  )
}

/** Shown to the two sides of a finished sale, who may see each other's phone. Everyone else just sees "sold". */
function SoldNote({ listing, isOwner }: { listing: Listing; isOwner: boolean }) {
  const other = listing.counterparty
  return (
    <div className="rounded-lg bg-surface p-3 text-sm">
      <p className="font-semibold text-ink">This item has been sold.</p>
      {other && (
        <p className="mt-1 text-muted">
          {isOwner ? 'Buyer' : 'Seller'}: {other.fullName}
          {other.phone ? <>, <a href={`tel:${other.phone}`} className="font-semibold text-ink underline underline-offset-2">{other.phone}</a></> : ', no phone on file'}
        </p>
      )}
    </div>
  )
}

function SellerCard({ listing }: { listing: Listing }) {
  // Fetched separately from the listing: the rating changes on its own and the listing is cached.
  const profile = useQuery({ queryKey: ['seller-profile', String(listing.owner.id)], queryFn: () => getSellerProfile(listing.owner.id), staleTime: 60_000 })
  const rating = profile.data
  return (
    <section aria-labelledby="seller-heading" className="rounded-xl border border-line bg-white p-5">
      <h2 id="seller-heading" className="sr-only">Seller</h2>
      <Link to={`/users/${listing.owner.id}`} className="flex items-center gap-3 rounded-lg hover:bg-surface">
        <Avatar name={listing.owner.fullName} size="lg" />
        <div className="min-w-0">
          <p className="truncate font-semibold text-ink">{listing.owner.fullName}</p>
          <p className="text-sm text-muted">Member since {formatMonthYear(listing.owner.memberSince)}</p>
          {rating && rating.averageRating !== null && (
            <p className="mt-0.5 flex items-center gap-1.5 text-sm text-ink">
              <Stars value={rating.averageRating} />
              <span className="tabular-nums">{rating.averageRating.toFixed(1)}</span>
              <span className="text-muted">({rating.ratingCount})</span>
            </p>
          )}
        </div>
      </Link>
      <Link to={`/users/${listing.owner.id}`} className="mt-4 inline-block text-sm font-semibold text-navy underline-offset-2 hover:underline">
        View profile and their listings
      </Link>
    </section>
  )
}

function SafetyTips() {
  return (
    <section aria-labelledby="safety-heading" className="rounded-xl bg-surface p-5">
      <h2 id="safety-heading" className="flex items-center gap-2 text-sm font-bold text-navy">
        <ShieldCheck className="size-4" aria-hidden />
        Stay safe
      </h2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted">
        <li>Meet on campus, in a public place.</li>
        <li>Inspect the item before you pay the balance.</li>
        <li>Keep payments and chat on Campus Marketplace.</li>
      </ul>
    </section>
  )
}

function DetailSkeleton() {
  return (
    <div aria-busy="true" aria-label="Loading listing" className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_380px]">
      <Skeleton className="aspect-[4/3] w-full rounded-xl" />
      <div className="space-y-4">
        <Skeleton className="h-6 w-24" />
        <Skeleton className="h-8 w-3/4" />
        <Skeleton className="h-9 w-32" />
        <Skeleton className="h-12 w-full" />
      </div>
    </div>
  )
}
