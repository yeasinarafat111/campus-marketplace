import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { CATEGORIES, searchListings, type SearchParams } from '../api/listings'
import Alert from '../components/Alert'
import ListingCard from '../components/ListingCard'
import Pagination from '../components/Pagination'
import { useFetch } from '../hooks/useFetch'
import { formatCategory } from '../lib'

const PAGE_SIZE = 12

export default function ListingsPage() {
  useDocumentTitle('Browse listings')
  const [params, setParams] = useSearchParams()
  const keyword = params.get('keyword') ?? ''
  const category = params.get('category') ?? ''
  const minPrice = params.get('minPrice') ?? ''
  const maxPrice = params.get('maxPrice') ?? ''
  const sortKey = params.get('sort') ?? 'newest'
  const page = Number(params.get('page') ?? 0) || 0
  const [search, setSearch] = useState(keyword)
  const [min, setMin] = useState(minPrice)
  const [max, setMax] = useState(maxPrice)

  const SORTS: Record<string, Pick<SearchParams, 'sort' | 'direction'>> = {
    newest: { sort: 'createdAt', direction: 'desc' },
    'price-asc': { sort: 'price', direction: 'asc' },
    'price-desc': { sort: 'price', direction: 'desc' },
    title: { sort: 'title', direction: 'asc' },
  }

  const { data, error, loading } = useFetch(
    () =>
      searchListings({
        keyword: keyword || undefined,
        category: category || undefined,
        minPrice: minPrice || undefined,
        maxPrice: maxPrice || undefined,
        ...(SORTS[sortKey] ?? SORTS.newest),
        page,
        size: PAGE_SIZE,
      }),
    [keyword, category, minPrice, maxPrice, sortKey, page],
  )

  function update(next: Record<string, string>) {
    const p = new URLSearchParams(params)
    for (const [k, v] of Object.entries(next)) {
      if (v) p.set(k, v)
      else p.delete(k)
    }
    setParams(p)
  }

  const onSearch = (e: FormEvent) => {
    e.preventDefault()
    update({ keyword: search.trim(), minPrice: min.trim(), maxPrice: max.trim(), page: '' })
  }

  return (
    <>
      <div className="mb-6 flex flex-wrap items-end justify-between gap-2">
        <h1 className="text-5xl leading-none">The board</h1>
        {data && (
          <p className="text-sm text-muted">
            {data.totalElements} {data.totalElements === 1 ? 'listing' : 'listings'} available
          </p>
        )}
      </div>

      <form onSubmit={onSearch} className="mb-6 grid gap-3 border-y border-rule py-4 sm:grid-cols-[1fr_auto_auto]">
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by title"
          aria-label="Search listings"
          className="input"
        />
        <select
          value={category}
          onChange={(e) => update({ category: e.target.value, page: '' })}
          aria-label="Category"
          className="input sm:w-auto"
        >
          <option value="">All categories</option>
          {CATEGORIES.map((c) => (
            <option key={c} value={c}>
              {formatCategory(c)}
            </option>
          ))}
        </select>
        <button className="btn btn-primary">Search</button>
        <div className="flex flex-wrap items-center gap-3 sm:col-span-3">
          <label className="flex items-center gap-2 text-sm font-semibold text-navy">
            Price from
            <input type="number" min="0" step="0.01" value={min} onChange={(e) => setMin(e.target.value)} className="input w-28" />
          </label>
          <label className="flex items-center gap-2 text-sm font-semibold text-navy">
            to
            <input type="number" min="0" step="0.01" value={max} onChange={(e) => setMax(e.target.value)} className="input w-28" />
          </label>
          <label className="flex items-center gap-2 text-sm font-semibold text-navy sm:ml-auto">
            Sort by
            <select value={sortKey} onChange={(e) => update({ sort: e.target.value === 'newest' ? '' : e.target.value, page: '' })} className="input w-auto">
              <option value="newest">Newest first</option>
              <option value="price-asc">Price, low to high</option>
              <option value="price-desc">Price, high to low</option>
              <option value="title">Title, A to Z</option>
            </select>
          </label>
        </div>
      </form>

      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <p className="text-muted">Loading listings…</p>}
      {data && data.content.length === 0 && (
        <div className="border-y border-rule py-10 text-center">
          <p className="font-display text-3xl text-navy">Nothing on the board matches</p>
          <p className="mt-1 text-muted">Try a different search, or clear the filters.</p>
          <button className="btn btn-outline mt-4" onClick={() => { setSearch(''); setMin(''); setMax(''); setParams({}) }}>
            Clear filters
          </button>
        </div>
      )}
      {data && data.content.length > 0 && (
        <div
          className={`grid border-l border-t-2 border-l-rule border-t-navy sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 ${
            loading ? 'opacity-60' : ''
          }`}
        >
          {data.content.map((l) => (
            <ListingCard key={l.id} listing={l} />
          ))}
        </div>
      )}
      {data && (
        <Pagination
          page={data.page}
          totalPages={data.totalPages}
          onChange={(p) => update({ page: p === 0 ? '' : String(p) })}
        />
      )}
    </>
  )
}
