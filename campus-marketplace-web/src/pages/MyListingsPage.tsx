import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { deleteListing, markSold, myListings } from '../api/listings'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import ListingPaymentActions from '../components/ListingPaymentActions'
import Pagination from '../components/Pagination'
import { useFetch } from '../hooks/useFetch'
import { formatCategory, formatPrice } from '../lib'

export default function MyListingsPage() {
  const [params, setParams] = useSearchParams()
  const page = Number(params.get('page') ?? 0) || 0
  const { data, error, loading, reload } = useFetch(() => myListings({ page, size: 10 }), [page])
  const [actionError, setActionError] = useState('')

  async function run(action: () => Promise<unknown>) {
    setActionError('')
    try {
      await action()
      reload()
    } catch (e) {
      setActionError(parseApiError(e).message)
    }
  }

  const onDelete = (id: number, title: string) => {
    if (window.confirm(`Delete "${title}"? This cannot be undone.`)) run(() => deleteListing(id))
  }
  const onSold = (id: number, title: string) => {
    if (window.confirm(`Mark "${title}" as sold?`)) run(() => markSold(id))
  }

  return (
    <>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">My listings</h1>
        <Link to="/listings/new" className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500">
          New listing
        </Link>
      </div>
      {(error || actionError) && <Alert kind="error">{error || actionError}</Alert>}
      {loading && !data && <p className="text-sm text-gray-500">Loading…</p>}
      {data && data.content.length === 0 && <p className="text-sm text-gray-500">You haven't posted anything yet.</p>}
      <ul className="space-y-3">
        {data?.content.map((l) => (
          <li key={l.id} className="flex flex-wrap items-center gap-4 rounded-xl bg-white p-4 shadow-sm ring-1 ring-gray-200">
            {l.imageUrl ? (
              <img src={l.imageUrl} alt="" className="h-16 w-16 rounded-md object-cover" />
            ) : (
              <div className="h-16 w-16 rounded-md bg-gray-100" />
            )}
            <div className="min-w-0 flex-1">
              <Link to={`/listings/${l.id}`} className="font-semibold text-gray-900 hover:underline">{l.title}</Link>
              <p className="text-sm text-gray-500">
                {formatPrice(l.price)} · {formatCategory(l.category)} ·{' '}
                <span className={l.status === 'SOLD' ? 'text-red-600' : 'text-green-600'}>{l.status === 'SOLD' ? 'Sold' : 'Active'}</span>
              </p>
            </div>
            <div className="flex gap-3 text-sm">
              <ListingPaymentActions listing={l} />
              <Link to={`/listings/${l.id}/edit`} className="text-indigo-600 hover:underline">Edit</Link>
              {l.status === 'ACTIVE' && (
                <button onClick={() => onSold(l.id, l.title)} className="text-indigo-600 hover:underline">Mark sold</button>
              )}
              <button onClick={() => onDelete(l.id, l.title)} className="text-red-600 hover:underline">Delete</button>
            </div>
          </li>
        ))}
      </ul>
      {data && (
        <Pagination page={data.page} totalPages={data.totalPages}
          onChange={(p) => setParams(p === 0 ? {} : { page: String(p) })} />
      )}
    </>
  )
}
