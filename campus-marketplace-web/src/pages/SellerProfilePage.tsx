import { useQuery } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { Star } from 'lucide-react'
import { useParams } from 'react-router-dom'
import { getSellerProfile, getSellerReviews, searchListings } from '../api/listings'
import { Stars } from '../components/StarRating'
import ListingCard from '../components/ListingCard'
import Avatar from '../components/ui/Avatar'
import Skeleton, { ListingCardSkeleton } from '../components/ui/Skeleton'
import EmptyState from '../components/ui/EmptyState'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { formatDate, formatMonthYear } from '../lib'
import NotFoundPage from './NotFoundPage'

/** Public: name, member since, counts and the seller's active listings. No contact details. */
export default function SellerProfilePage() {
  const { id = '' } = useParams()
  const profile = useQuery({ queryKey: ['seller-profile', id], queryFn: () => getSellerProfile(id), retry: false })
  const listings = useQuery({
    queryKey: ['listings', { seller: id, profile: true }],
    queryFn: () => searchListings({ ownerId: Number(id), size: 40 }),
    enabled: profile.isSuccess,
  })
  const reviews = useQuery({
    queryKey: ['seller-reviews', id],
    queryFn: () => getSellerReviews(id, { size: 10 }),
    enabled: profile.isSuccess && (profile.data?.ratingCount ?? 0) > 0,
  })
  useDocumentTitle(profile.data?.fullName ?? 'Seller')

  if (profile.error && isAxiosError(profile.error) && profile.error.response?.status === 404) return <NotFoundPage />
  if (profile.isPending) return <Skeleton className="h-40 w-full rounded-xl" />
  if (!profile.data) return <p role="alert" className="text-sm text-danger">Couldn't load this profile. Try again in a moment.</p>
  const p = profile.data

  return (
    <div>
      <section className="flex flex-wrap items-center gap-4 rounded-xl border border-line bg-white p-5">
        <Avatar name={p.fullName} size="lg" />
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-2xl font-bold tracking-tight text-navy">{p.fullName}</h1>
          <p className="text-sm text-muted">Member since {formatMonthYear(p.memberSince)}</p>
        </div>
        <dl className="grid w-full grid-cols-3 gap-3 text-center sm:w-auto">
          <Stat label="Active listings" value={String(p.activeListings)} />
          <Stat label="Items sold" value={String(p.itemsSold)} />
          <Stat label="Rating" value={p.averageRating === null ? '—' : p.averageRating.toFixed(1)}
            hint={p.ratingCount > 0 ? `${p.ratingCount} rating${p.ratingCount === 1 ? '' : 's'}` : 'No ratings yet'}
            star={p.averageRating !== null} />
        </dl>
      </section>

      {reviews.data && reviews.data.content.length > 0 && (
        <section aria-labelledby="reviews-heading" className="mt-8">
          <h2 id="reviews-heading" className="mb-3 text-lg font-semibold text-ink">Reviews</h2>
          <ul className="divide-y divide-line rounded-xl border border-line bg-white">
            {reviews.data.content.map((r, i) => (
              <li key={i} className="p-4">
                <p className="flex flex-wrap items-center gap-2 text-sm">
                  <Stars value={r.stars} />
                  <span className="font-medium text-ink">{r.reviewer}</span>
                  <span className="text-muted">{formatDate(r.createdAt)}</span>
                </p>
                {r.comment && <p className="mt-1 text-sm text-ink">{r.comment}</p>}
              </li>
            ))}
          </ul>
        </section>
      )}

      <h2 className="mb-4 mt-8 text-lg font-semibold text-ink">Listings</h2>
      {listings.isPending && (
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {Array.from({ length: 4 }, (_, i) => <ListingCardSkeleton key={i} />)}
        </div>
      )}
      {listings.data && listings.data.content.length === 0 && (
        <EmptyState icon={Star} title="No active listings">This seller doesn't have anything for sale right now.</EmptyState>
      )}
      {listings.data && listings.data.content.length > 0 && (
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {listings.data.content.map((l) => <ListingCard key={l.id} listing={l} />)}
        </div>
      )}
    </div>
  )
}

function Stat({ label, value, hint, star }: { label: string; value: string; hint?: string; star?: boolean }) {
  return (
    <div className="min-w-20 rounded-lg bg-surface px-3 py-2">
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="flex items-center justify-center gap-1 text-lg font-bold tabular-nums text-ink">
        {star && <Star className="size-4 fill-amber-500 text-amber-500" aria-hidden />}
        {value}
      </dd>
      {hint && <p className="text-[11px] text-muted">{hint}</p>}
    </div>
  )
}
