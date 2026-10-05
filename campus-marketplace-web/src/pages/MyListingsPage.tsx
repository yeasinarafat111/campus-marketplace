import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Package, Pencil, Plus, Star, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { deleteListing, markSold, myListings, type Listing } from '../api/listings'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import CheckoutButton from '../components/CheckoutButton'
import ListingBadges from '../components/ListingBadges'
import Pagination from '../components/Pagination'
import Button from '../components/ui/Button'
import { buttonClasses } from '../components/ui/buttonClasses'
import { ConfirmDialog } from '../components/ui/Dialog'
import EmptyState from '../components/ui/EmptyState'
import ImageWithFallback from '../components/ui/ImageWithFallback'
import PriceTag from '../components/ui/PriceTag'
import Skeleton from '../components/ui/Skeleton'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { cx, formatCategory, formatDate } from '../lib'

const TABS = {
  active: { label: 'Active', query: { status: 'ACTIVE', reserved: false } },
  reserved: { label: 'Reserved', query: { status: 'ACTIVE', reserved: true } },
  sold: { label: 'Sold', query: { status: 'SOLD' } },
} as const
type Tab = keyof typeof TABS

type Pending = { kind: 'sold' | 'delete'; listing: Listing }

export default function MyListingsPage() {
  useDocumentTitle('My listings')
  const queryClient = useQueryClient()
  const [params, setParams] = useSearchParams()
  const tab: Tab = (params.get('tab') as Tab) in TABS ? (params.get('tab') as Tab) : 'active'
  const page = Number(params.get('page') ?? 0) || 0
  const [pending, setPending] = useState<Pending | null>(null)

  const { data, error, isPending } = useQuery({
    queryKey: ['my-listings', tab, page],
    queryFn: () => myListings({ ...TABS[tab].query, page, size: 10 }),
  })

  const confirm = useMutation({
    mutationFn: async ({ kind, listing }: Pending) => {
      if (kind === 'sold') await markSold(listing.id)
      else await deleteListing(listing.id)
    },
    onSuccess: (_result, { kind, listing }) => {
      toast.success(kind === 'sold' ? `“${listing.title}” marked as sold` : `“${listing.title}” deleted`)
      setPending(null)
      queryClient.invalidateQueries({ queryKey: ['my-listings'] })
      queryClient.invalidateQueries({ queryKey: ['listings'] })
      queryClient.invalidateQueries({ queryKey: ['listing', String(listing.id)] })
    },
    onError: (e) => {
      toast.error(parseApiError(e).message)
      setPending(null)
    },
  })

  const selectTab = (next: Tab) => setParams(next === 'active' ? {} : { tab: next })

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-6 flex items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight text-navy">My listings</h1>
        <Link to="/listings/new" className={buttonClasses()}>
          <Plus className="size-4" aria-hidden />
          New listing
        </Link>
      </div>

      <div role="tablist" aria-label="Listing status" className="mb-2 flex gap-1 border-b border-line">
        {(Object.keys(TABS) as Tab[]).map((key) => {
          const selected = key === tab
          return (
            <button
              key={key}
              role="tab"
              id={`tab-${key}`}
              aria-selected={selected}
              aria-controls="my-listings-panel"
              onClick={() => selectTab(key)}
              className={cx(
                '-mb-px border-b-2 px-4 py-2.5 text-sm font-semibold transition-colors duration-150',
                selected ? 'border-navy text-navy' : 'border-transparent text-muted hover:text-ink',
              )}
            >
              {TABS[key].label}
            </button>
          )
        })}
      </div>

      <div id="my-listings-panel" role="tabpanel" aria-labelledby={`tab-${tab}`}>
        {error && <Alert kind="error">{parseApiError(error).message}</Alert>}
        {isPending && (
          <ul className="divide-y divide-line">
            {[0, 1, 2].map((i) => (
              <li key={i} className="flex gap-4 py-4">
                <Skeleton className="size-20 shrink-0" />
                <div className="flex-1 space-y-2">
                  <Skeleton className="h-5 w-1/2" />
                  <Skeleton className="h-4 w-20" />
                </div>
              </li>
            ))}
          </ul>
        )}
        {data && data.content.length === 0 && (
          <div className="mt-6">
            <EmptyState
              icon={Package}
              title={tab === 'active' ? "You don't have any active listings" : tab === 'reserved' ? 'Nothing is reserved right now' : 'Nothing sold yet'}
              action={tab === 'active' ? <Link to="/listings/new" className={buttonClasses()}>Sell something</Link> : undefined}
            >
              {tab === 'active' ? 'Post an item and it shows up on the board straight away.' : tab === 'reserved' ? 'When a buyer pays a deposit, the item shows up here.' : 'Items you mark as sold show up here.'}
            </EmptyState>
          </div>
        )}
        <ul className="divide-y divide-line">
          {data?.content.map((l) => (
            <li key={l.id} className="flex flex-col gap-3 py-4 sm:flex-row sm:items-center">
              <div className="flex min-w-0 flex-1 items-center gap-4">
                <Link to={`/listings/${l.id}`} className="shrink-0">
                  <ImageWithFallback src={l.imageUrl} alt={l.title} category={l.category} className="size-20 rounded-lg" />
                </Link>
                <div className="min-w-0">
                  <Link to={`/listings/${l.id}`} className="block truncate font-semibold text-ink hover:underline">
                    {l.title}
                  </Link>
                  <PriceTag price={l.price} size="md" />
                  <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted">
                    <ListingBadges listing={l} showAvailable />
                    <span>{formatCategory(l.category)}</span>
                    {l.featured && l.featuredUntil && <span>· Featured until {formatDate(l.featuredUntil)}</span>}
                  </div>
                </div>
              </div>
              {l.status === 'ACTIVE' && (
                <div className="flex flex-wrap items-center gap-2">
                  {!l.featured && (
                    <CheckoutButton listingId={l.id} purpose="FEATURE_LISTING" variant="accent" size="sm">
                      <Star className="size-4" aria-hidden />
                      Feature
                    </CheckoutButton>
                  )}
                  <Link to={`/listings/${l.id}/edit`} className={buttonClasses({ variant: 'secondary', size: 'sm' })}>
                    <Pencil className="size-4" aria-hidden />
                    Edit
                  </Link>
                  <Button variant="secondary" size="sm" onClick={() => setPending({ kind: 'sold', listing: l })}>
                    Mark sold
                  </Button>
                  <Button variant="ghost" size="sm" aria-label={`Delete ${l.title}`} className="text-danger hover:bg-danger-soft" onClick={() => setPending({ kind: 'delete', listing: l })}>
                    <Trash2 className="size-4" aria-hidden />
                    Delete
                  </Button>
                </div>
              )}
              {l.status === 'SOLD' && (
                <Button variant="ghost" size="sm" aria-label={`Delete ${l.title}`} className="self-start text-danger hover:bg-danger-soft" onClick={() => setPending({ kind: 'delete', listing: l })}>
                  <Trash2 className="size-4" aria-hidden />
                  Delete
                </Button>
              )}
            </li>
          ))}
        </ul>
        {data && (
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            onChange={(p) => setParams({ ...(tab === 'active' ? {} : { tab }), ...(p === 0 ? {} : { page: String(p) }) })}
          />
        )}
      </div>

      <ConfirmDialog
        open={pending !== null}
        onOpenChange={(open) => !open && !confirm.isPending && setPending(null)}
        title={pending?.kind === 'delete' ? 'Delete this listing?' : 'Mark as sold?'}
        description={
          pending?.kind === 'delete'
            ? `“${pending.listing.title}” will be removed for good. This can't be undone.`
            : `“${pending?.listing.title}” will be taken off the board.`
        }
        confirmLabel={pending?.kind === 'delete' ? 'Delete' : 'Mark as sold'}
        danger={pending?.kind === 'delete'}
        loading={confirm.isPending}
        onConfirm={() => pending && confirm.mutate(pending)}
      />
    </div>
  )
}
