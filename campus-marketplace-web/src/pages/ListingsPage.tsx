import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { CATEGORIES, searchListings } from '../api/listings'
import Alert from '../components/Alert'
import ListingCard from '../components/ListingCard'
import Pagination from '../components/Pagination'
import { useFetch } from '../hooks/useFetch'
import { formatCategory } from '../lib'

const PAGE_SIZE = 12

export default function ListingsPage() {
  const [params, setParams] = useSearchParams()
  const keyword = params.get('keyword') ?? ''
  const category = params.get('category') ?? ''
  const page = Number(params.get('page') ?? 0) || 0
  const [search, setSearch] = useState(keyword)

  const { data, error, loading } = useFetch(
    () => searchListings({ keyword: keyword || undefined, category: category || undefined, page, size: PAGE_SIZE }),
    [keyword, category, page],
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
    update({ keyword: search.trim(), page: '' })
  }

  return (
    <>
      <form onSubmit={onSearch} className="mb-6 flex flex-wrap gap-3">
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search listings…"
          aria-label="Search listings"
          className="min-w-0 flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm"
        />
        <select
          value={category}
          onChange={(e) => update({ category: e.target.value, page: '' })}
          aria-label="Category"
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm"
        >
          <option value="">All categories</option>
          {CATEGORIES.map((c) => (
            <option key={c} value={c}>
              {formatCategory(c)}
            </option>
          ))}
        </select>
        <button className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500">
          Search
        </button>
      </form>

      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <p className="text-sm text-gray-500">Loading…</p>}
      {data && data.content.length === 0 && <p className="text-sm text-gray-500">No listings found.</p>}
      {data && (
        <div className={`grid gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 ${loading ? 'opacity-60' : ''}`}>
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
