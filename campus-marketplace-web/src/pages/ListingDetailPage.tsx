import { Link, useParams } from 'react-router-dom'
import { getListing } from '../api/listings'
import Alert from '../components/Alert'
import ListingPaymentActions from '../components/ListingPaymentActions'
import { useAuth } from '../hooks/useAuth'
import { useFetch } from '../hooks/useFetch'
import { formatCategory, formatPrice } from '../lib'

export default function ListingDetailPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const { data: listing, error, loading } = useFetch(() => getListing(id!), [id])

  if (loading) return <p className="text-sm text-gray-500">Loading…</p>
  if (error) return <Alert kind="error">{error}</Alert>
  if (!listing) return null

  const isOwner = user?.id === listing.owner.id

  return (
    <div className="grid gap-8 md:grid-cols-2">
      {listing.imageUrl ? (
        <img src={listing.imageUrl} alt={listing.title} className="w-full rounded-xl object-cover ring-1 ring-gray-200" />
      ) : (
        <div className="flex h-72 items-center justify-center rounded-xl bg-gray-100 text-gray-400">No image</div>
      )}
      <div>
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="rounded-full bg-gray-100 px-2 py-0.5 text-gray-600">{formatCategory(listing.category)}</span>
          {listing.status === 'SOLD' && <span className="rounded-full bg-red-100 px-2 py-0.5 text-red-700">Sold</span>}
          {listing.reserved && <span className="rounded-full bg-amber-100 px-2 py-0.5 text-amber-700">Reserved</span>}
          {listing.featured && <span className="rounded-full bg-indigo-100 px-2 py-0.5 text-indigo-700">Featured</span>}
        </div>
        <h1 className="mt-3 text-3xl font-bold text-gray-900">{listing.title}</h1>
        <p className="mt-2 text-2xl font-semibold text-indigo-600">{formatPrice(listing.price)}</p>
        {listing.description && <p className="mt-4 whitespace-pre-line text-gray-700">{listing.description}</p>}
        <p className="mt-6 text-sm text-gray-500">
          Sold by {listing.owner.fullName} · listed {new Date(listing.createdAt).toLocaleDateString()}
        </p>
        <ListingPaymentActions listing={listing} />
        {isOwner && (
          <Link
            to={`/listings/${listing.id}/edit`}
            className="mt-6 inline-block rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium hover:bg-gray-50"
          >
            Edit listing
          </Link>
        )}
      </div>
    </div>
  )
}
