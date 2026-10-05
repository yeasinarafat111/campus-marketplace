import { useQuery } from '@tanstack/react-query'
import { Phone, Receipt } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { getOrders, type Order, type OrderKind } from '../api/orders'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import Pagination from '../components/Pagination'
import Badge from '../components/ui/Badge'
import { buttonClasses } from '../components/ui/buttonClasses'
import EmptyState from '../components/ui/EmptyState'
import ImageWithFallback from '../components/ui/ImageWithFallback'
import Skeleton from '../components/ui/Skeleton'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { formatCents, formatDate } from '../lib'

const COPY: Record<OrderKind, { title: string; empty: string; emptyHint: string; other: string; money: string }> = {
  purchases: {
    title: 'Purchases',
    empty: "You haven't bought anything yet",
    emptyHint: 'Reserve an item, then buy it, and the order shows up here.',
    other: 'Seller',
    money: 'You paid',
  },
  sales: {
    title: 'Sales',
    empty: "You haven't sold anything yet",
    emptyHint: 'When a student buys one of your items, the order shows up here.',
    other: 'Buyer',
    money: 'You are owed',
  },
}

/** Purchases or sales: order cards with the price breakdown (item, deposit, balance) and the other side. */
export default function OrdersPage({ kind }: { kind: OrderKind }) {
  const copy = COPY[kind]
  useDocumentTitle(copy.title)
  const [page, setPage] = useState(0)
  const { data, error, isPending } = useQuery({
    queryKey: ['orders', kind, page],
    queryFn: () => getOrders(kind, { page, size: 10 }),
  })

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="mb-6 text-2xl font-bold tracking-tight text-navy">{copy.title}</h1>
      {error && <Alert kind="error">{parseApiError(error).message}</Alert>}
      {isPending && (
        <div className="space-y-4">
          {[0, 1].map((i) => <Skeleton key={i} className="h-40 w-full rounded-xl" />)}
        </div>
      )}
      {data && data.content.length === 0 && (
        <EmptyState
          icon={Receipt}
          title={copy.empty}
          action={kind === 'purchases' ? <Link to="/listings" className={buttonClasses()}>Browse listings</Link> : undefined}
        >
          {copy.emptyHint}
        </EmptyState>
      )}
      <ul className="space-y-4">
        {data?.content.map((o) => <OrderCard key={o.id} order={o} kind={kind} />)}
      </ul>
      {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
    </div>
  )
}

function OrderCard({ order, kind }: { order: Order; kind: OrderKind }) {
  const copy = COPY[kind]
  const cents = (n: number) => formatCents(n)
  return (
    <li className="rounded-xl border border-line p-4 sm:p-5">
      <div className="flex gap-4">
        <ImageWithFallback src={order.listingImageUrl} alt={order.listingTitle} className="size-20 shrink-0 rounded-lg" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-start justify-between gap-2">
            {order.listingId ? (
              <Link to={`/listings/${order.listingId}`} className="font-semibold text-ink hover:underline">{order.listingTitle}</Link>
            ) : (
              <span className="font-semibold text-ink">{order.listingTitle}</span>
            )}
            <StatusBadge order={order} kind={kind} />
          </div>
          <p className="mt-0.5 text-sm text-muted">{formatDate(order.createdAt)}</p>
          <p className="mt-2 flex flex-wrap items-center gap-x-1.5 text-sm">
            <Phone className="size-3.5 text-muted" aria-hidden />
            {copy.other}: {order.counterparty.fullName}
            {order.counterparty.phone ? (
              <>, <a href={`tel:${order.counterparty.phone}`} className="font-semibold underline underline-offset-2">{order.counterparty.phone}</a></>
            ) : (
              <span className="text-muted">, no phone on file</span>
            )}
          </p>
        </div>
      </div>
      <dl className="mt-4 grid grid-cols-3 gap-3 rounded-lg bg-surface p-3 text-sm">
        <Amount label="Item price" value={cents(order.itemPriceCents)} />
        <Amount label="Deposit" value={cents(order.depositCents)} />
        <Amount label="Balance" value={cents(order.balanceCents)} />
      </dl>
      <p className="mt-2 text-xs text-muted">
        {copy.money} {cents(order.itemPriceCents)} in total
        {kind === 'sales' && order.payoutStatus === 'PENDING' ? ' (payout pending)' : ''}.
      </p>
    </li>
  )
}

function Amount({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="font-semibold tabular-nums text-ink">{value}</dd>
    </div>
  )
}

function StatusBadge({ order, kind }: { order: Order; kind: OrderKind }) {
  if (order.status === 'CANCELLED') return <Badge tone="danger">Cancelled</Badge>
  if (kind === 'sales') {
    return order.payoutStatus === 'PAID_OUT' ? <Badge tone="success">Paid out</Badge> : <Badge tone="warning">Payout pending</Badge>
  }
  return <Badge tone="success">Paid</Badge>
}
