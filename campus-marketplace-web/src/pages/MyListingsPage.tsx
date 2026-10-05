import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { deleteListing, markSold, myListings, type ListingStatus } from '../api/listings'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import ListingBadges from '../components/ListingBadges'
import ListingPaymentActions from '../components/ListingPaymentActions'
import Pagination from '../components/Pagination'
import { useFetch } from '../hooks/useFetch'
import { formatPrice } from '../lib'

export default function MyListingsPage() {
  useDocumentTitle('My listings')
  const [params, setParams] = useSearchParams()
  const page = Number(params.get('page') ?? 0) || 0
  const status = (params.get('status') as ListingStatus | null) ?? undefined
  const { data, error, loading, reload } = useFetch(() => myListings({ status, page, size: 10 }), [status, page])
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
      <div className="mb-6 flex items-end justify-between gap-3">
        <h1 className="text-5xl leading-none">My listings</h1>
        <Link to="/listings/new" className="btn btn-primary">
          New listing
        </Link>
      </div>
      <div role="tablist" aria-label="Filter by status" className="mb-4 flex gap-5 border-b border-rule">
        {([['', 'All'], ['ACTIVE', 'Active'], ['SOLD', 'Sold']] as const).map(([value, label]) => {
          const selected = (status ?? '') === value
          return (
            <button
              key={label}
              role="tab"
              aria-selected={selected}
              onClick={() => setParams(value ? { status: value } : {})}
              className={`-mb-px border-b-2 py-2 text-base font-semibold ${selected ? 'border-gold text-navy' : 'border-transparent text-muted hover:text-navy'}`}
            >
              {label}
            </button>
          )
        })}
      </div>
      {(error || actionError) && <Alert kind="error">{error || actionError}</Alert>}
      {loading && !data && <p className="text-muted">Loading…</p>}
      {data && data.content.length === 0 && <p className="text-muted">{status ? 'No listings with this status.' : "You haven't posted anything yet."}</p>}
      <ul className="border-t-2 border-navy">
        {data?.content.map((l) => (
          <li
            key={l.id}
            className={`flex flex-wrap items-center gap-4 border-b border-rule py-4 ${l.featured ? 'border-l-4 border-l-gold bg-gold/10 pl-3' : ''}`}
          >
            {l.imageUrl ? (
              <img src={l.imageUrl} alt="" className="h-20 w-20 border border-rule object-cover" />
            ) : (
              <div className="h-20 w-20 border border-dashed border-rule" />
            )}
            <div className="min-w-0 flex-1">
              <Link to={`/listings/${l.id}`} className="text-lg font-semibold text-ink hover:underline">
                {l.title}
              </Link>
              <p className="font-display text-2xl font-bold text-navy">{formatPrice(l.price)}</p>
              <ListingBadges listing={l} />
            </div>
            <div className="flex flex-wrap items-start gap-x-4 gap-y-2 text-sm">
              <ListingPaymentActions listing={l} />
              <Link to={`/listings/${l.id}/edit`} className="link py-2">
                Edit
              </Link>
              {l.status === 'ACTIVE' && (
                <button onClick={() => onSold(l.id, l.title)} className="link py-2">
                  Mark sold
                </button>
              )}
              <button onClick={() => onDelete(l.id, l.title)} className="link-danger py-2">
                Delete
              </button>
            </div>
          </li>
        ))}
      </ul>
      {data && (
        <Pagination page={data.page} totalPages={data.totalPages} onChange={(p) => setParams({ ...(status ? { status } : {}), ...(p === 0 ? {} : { page: String(p) }) })} />
      )}
    </>
  )
}
