import { useQuery } from '@tanstack/react-query'
import { PackageSearch, SlidersHorizontal, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { CATEGORIES, CONDITION_LABELS, ITEM_CONDITIONS, getCategoryCounts, getFeatured, searchListings, type Category, type ItemCondition, type SearchParams } from '../api/listings'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import { CATEGORY_ICONS } from '../components/categoryIcons'
import ListingCard from '../components/ListingCard'
import Pagination from '../components/Pagination'
import Button from '../components/ui/Button'
import { buttonClasses } from '../components/ui/buttonClasses'
import Dialog from '../components/ui/Dialog'
import EmptyState from '../components/ui/EmptyState'
import { Input, Select } from '../components/ui/Input'
import { ListingCardSkeleton } from '../components/ui/Skeleton'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { cx, formatCategory } from '../lib'

const PAGE_SIZE = 20

// "recommended" sends no sort, which the backend turns into: featured first, then newest.
const SORTS: Record<string, { label: string; params: Pick<SearchParams, 'sort' | 'direction'> }> = {
  recommended: { label: 'Recommended', params: {} },
  newest: { label: 'Newest', params: { sort: 'createdAt', direction: 'desc' } },
  'price-asc': { label: 'Price: low to high', params: { sort: 'price', direction: 'asc' } },
  'price-desc': { label: 'Price: high to low', params: { sort: 'price', direction: 'desc' } },
}

const GRID = 'grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5'

export default function ListingsPage() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const category = (params.get('category') ?? '') as Category | ''
  const minPrice = params.get('minPrice') ?? ''
  const maxPrice = params.get('maxPrice') ?? ''
  const condition = (params.get('condition') ?? '') as ItemCondition | ''
  const sortKey = params.get('sort') && SORTS[params.get('sort')!] ? params.get('sort')! : 'recommended'
  const seller = Number(params.get('seller')) || undefined
  const page = Number(params.get('page') ?? 0) || 0
  const [filtersOpen, setFiltersOpen] = useState(false)

  const hasFilters = Boolean(q || category || minPrice || maxPrice || seller || condition)
  useDocumentTitle(q ? `“${q}”` : 'Browse listings')

  const results = useQuery({
    queryKey: ['listings', { q, category, minPrice, maxPrice, condition, sortKey, seller, page }],
    queryFn: () =>
      searchListings({
        keyword: q || undefined,
        category: category || undefined,
        minPrice: minPrice || undefined,
        maxPrice: maxPrice || undefined,
        ownerId: seller,
        condition: condition || undefined,
        ...SORTS[sortKey].params,
        page,
        size: PAGE_SIZE,
      }),
    placeholderData: (previous) => previous,
  })

  // The Featured row is the front page's hook; it is hidden as soon as the visitor narrows things down.
  const showFeaturedRow = !hasFilters && sortKey === 'recommended' && page === 0
  const featured = useQuery({
    queryKey: ['listings', 'featured'],
    queryFn: getFeatured,
    enabled: showFeaturedRow,
  })
  const featuredRow = showFeaturedRow ? featured.data ?? [] : []
  const counts = useQuery({ queryKey: ['listings', 'category-counts'], queryFn: getCategoryCounts })
  // The default order already puts featured listings first, so don't repeat them under the row.
  const gridItems =
    featuredRow.length > 0 ? results.data?.content.filter((l) => !l.featured) : results.data?.content

  function update(next: Record<string, string>) {
    const p = new URLSearchParams(params)
    for (const [k, v] of Object.entries(next)) {
      if (v) p.set(k, v)
      else p.delete(k)
    }
    p.delete('page')
    setParams(p)
  }

  const sellerName = seller ? results.data?.content[0]?.owner.fullName : undefined

  return (
    <>
      <h1 className="sr-only">Browse listings</h1>
      <CategoryChips selected={category} counts={counts.data} onSelect={(c) => update({ category: c })} />

      {showFeaturedRow && featuredRow.length > 0 && (
        <section aria-labelledby="featured-heading" className="mt-6">
          <h2 id="featured-heading" className="mb-3 text-lg font-bold text-navy">
            Featured
          </h2>
          <ul className="scrollbar-none -mx-4 flex gap-4 overflow-x-auto px-4 pb-1">
            {featuredRow.map((l) => (
              <li key={l.id} className="w-40 shrink-0 sm:w-48">
                <ListingCard listing={l} />
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="results-heading" className="mt-8">
        <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2">
          <div className="min-w-0 flex-1">
            <h2 id="results-heading" className="truncate text-xl font-bold text-navy">
              {seller ? `Listings from ${sellerName ?? 'this seller'}` : q ? `Results for “${q}”` : category ? formatCategory(category) : 'Fresh listings'}
            </h2>
            <p className="text-sm text-muted" aria-live="polite">
              {results.data
                ? `${results.data.totalElements} ${results.data.totalElements === 1 ? 'listing' : 'listings'}`
                : ' '}
            </p>
          </div>
          <FilterBar
            key={`${minPrice}-${maxPrice}-${sortKey}-${condition}`}
            className="hidden md:flex"
            minPrice={minPrice}
            maxPrice={maxPrice}
            condition={condition}
            sortKey={sortKey}
            onApply={update}
          />
          <Button variant="secondary" className="md:hidden" onClick={() => setFiltersOpen(true)}>
            <SlidersHorizontal className="size-4" aria-hidden />
            Filters
          </Button>
        </div>

        {hasFilters && (
          <div className="mb-4 flex flex-wrap gap-2">
            {q && <Chip label={`Search: ${q}`} onClear={() => update({ q: '' })} />}
            {seller && <Chip label="One seller" onClear={() => update({ seller: '' })} />}
            {condition && <Chip label={`Condition: ${CONDITION_LABELS[condition]}`} onClear={() => update({ condition: '' })} />}
            {(minPrice || maxPrice) && (
              <Chip
                label={`Price: ${minPrice ? `$${minPrice}` : '$0'} – ${maxPrice ? `$${maxPrice}` : 'any'}`}
                onClear={() => update({ minPrice: '', maxPrice: '' })}
              />
            )}
          </div>
        )}

        {results.error && !results.data && <Alert kind="error">{parseApiError(results.error).message}</Alert>}

        {results.isPending && (
          <div className={GRID}>
            {Array.from({ length: 10 }, (_, i) => (
              <ListingCardSkeleton key={i} />
            ))}
          </div>
        )}

        {results.data && results.data.totalElements === 0 && (
          <EmptyState
            icon={PackageSearch}
            title={hasFilters ? 'Nothing matches those filters' : 'No listings yet'}
            action={
              <div className="flex flex-wrap justify-center gap-2">
                {hasFilters && (
                  <Button variant="secondary" onClick={() => setParams({})}>
                    Clear filters
                  </Button>
                )}
                <Link to="/listings/new" className={buttonClasses()}>
                  Sell something
                </Link>
              </div>
            }
          >
            {hasFilters ? 'Try a different search, or widen the price range.' : 'Be the first to put something up for sale.'}
          </EmptyState>
        )}

        {gridItems && gridItems.length > 0 && (
          <div className={cx(GRID, results.isFetching && 'opacity-60 transition-opacity')}>
            {gridItems.map((l) => (
              <ListingCard key={l.id} listing={l} />
            ))}
          </div>
        )}

        {results.data && (
          <Pagination
            page={results.data.page}
            totalPages={results.data.totalPages}
            onChange={(p) => {
              const next = new URLSearchParams(params)
              if (p === 0) next.delete('page')
              else next.set('page', String(p))
              setParams(next)
              window.scrollTo({ top: 0 })
            }}
          />
        )}
      </section>

      <Dialog open={filtersOpen} onOpenChange={setFiltersOpen} title="Filters" side="bottom">
        <FilterBar
          key={`m-${minPrice}-${maxPrice}-${sortKey}-${condition}`}
          stacked
          minPrice={minPrice}
          maxPrice={maxPrice}
          condition={condition}
          sortKey={sortKey}
          onApply={(next) => {
            update(next)
            setFiltersOpen(false)
          }}
        />
      </Dialog>
    </>
  )
}

function CategoryChips({ selected, counts, onSelect }: { selected: string; counts?: Record<Category, number>; onSelect: (category: string) => void }) {
  const chip = (active: boolean) =>
    cx(
      'flex shrink-0 items-center gap-2 rounded-full border px-4 py-2 text-sm font-medium transition-colors duration-150',
      active ? 'border-navy bg-navy text-white' : 'border-line bg-white text-ink hover:bg-surface',
    )
  return (
    <nav aria-label="Categories">
      <ul className="scrollbar-none -mx-4 flex gap-2 overflow-x-auto px-4 py-1">
        <li>
          <button type="button" onClick={() => onSelect('')} aria-pressed={selected === ''} className={chip(selected === '')}>
            All
          </button>
        </li>
        {CATEGORIES.map((c) => {
          const Icon = CATEGORY_ICONS[c]
          return (
            <li key={c}>
              <button type="button" onClick={() => onSelect(c)} aria-pressed={selected === c} className={chip(selected === c)}>
                <Icon className="size-4" aria-hidden />
                {formatCategory(c)}
                {counts && <span className={selected === c ? 'text-white/70' : 'text-muted'}>{counts[c]}</span>}
              </button>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

function Chip({ label, onClear }: { label: string; onClear: () => void }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-navy-soft py-1 pl-3 pr-1 text-sm text-navy">
      {label}
      <button type="button" onClick={onClear} aria-label={`Remove filter: ${label}`} className="rounded-full p-1 transition-colors hover:bg-white">
        <X className="size-3.5" aria-hidden />
      </button>
    </span>
  )
}

/** Price range and sort. Applied together with the button, so typing a price doesn't refetch per keystroke. */
function FilterBar({
  minPrice,
  maxPrice,
  condition,
  sortKey,
  onApply,
  stacked = false,
  className,
}: {
  minPrice: string
  maxPrice: string
  condition: string
  sortKey: string
  onApply: (next: Record<string, string>) => void
  stacked?: boolean
  className?: string
}) {
  const [min, setMin] = useState(minPrice)
  const [max, setMax] = useState(maxPrice)
  const [sort, setSort] = useState(sortKey)
  const [cond, setCond] = useState(condition)

  function submit(e: FormEvent) {
    e.preventDefault()
    onApply({ minPrice: min.trim(), maxPrice: max.trim(), condition: cond, sort: sort === 'recommended' ? '' : sort })
  }

  return (
    <form onSubmit={submit} className={cx(stacked ? 'space-y-4' : 'items-center gap-2', className)}>
      <div className="flex items-center gap-2">
        <Input type="number" inputMode="decimal" min="0" step="0.01" value={min} onChange={(e) => setMin(e.target.value)} placeholder="Min $" aria-label="Minimum price" className="w-24" />
        <span aria-hidden className="text-muted">–</span>
        <Input type="number" inputMode="decimal" min="0" step="0.01" value={max} onChange={(e) => setMax(e.target.value)} placeholder="Max $" aria-label="Maximum price" className="w-24" />
      </div>
      <Select value={cond} onChange={(e) => setCond(e.target.value)} aria-label="Condition" className={stacked ? '' : 'w-36'}>
        <option value="">Any condition</option>
        {ITEM_CONDITIONS.map((c) => <option key={c} value={c}>{CONDITION_LABELS[c]}</option>)}
      </Select>
      <Select value={sort} onChange={(e) => setSort(e.target.value)} aria-label="Sort by" className={stacked ? '' : 'w-48'}>
        {Object.entries(SORTS).map(([key, { label }]) => (
          <option key={key} value={key}>
            {label}
          </option>
        ))}
      </Select>
      <Button type="submit" className={stacked ? 'w-full' : ''}>
        Apply
      </Button>
    </form>
  )
}
