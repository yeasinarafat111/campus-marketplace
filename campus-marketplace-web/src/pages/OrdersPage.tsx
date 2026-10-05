import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MapPin, Phone, Receipt } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Link } from 'react-router-dom'
import { MEETUP_LABELS } from '../api/listings'
import { confirmReceipt, getOrders, reportProblem, type Order, type OrderKind } from '../api/orders'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import Pagination from '../components/Pagination'
import Badge from '../components/ui/Badge'
import Button from '../components/ui/Button'
import Dialog, { ConfirmDialog } from '../components/ui/Dialog'
import { Textarea } from '../components/ui/Input'
import Field from '../components/ui/Field'
import { buttonClasses } from '../components/ui/buttonClasses'
import EmptyState from '../components/ui/EmptyState'
import ImageWithFallback from '../components/ui/ImageWithFallback'
import Skeleton from '../components/ui/Skeleton'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { formatCents, formatDate, formatDateTime } from '../lib'

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
          {order.meetupSpot && (
            <p className="mt-1 flex items-center gap-x-1.5 text-sm">
              <MapPin className="size-3.5 text-muted" aria-hidden />
              Meetup spot: {MEETUP_LABELS[order.meetupSpot]}
            </p>
          )}
        </div>
      </div>
      <dl className="mt-4 grid grid-cols-3 gap-3 rounded-lg bg-surface p-3 text-sm">
        <Amount label="Item price" value={cents(order.itemPriceCents)} />
        <Amount label={order.paymentMethod === 'CASH' ? 'Deposit (online)' : 'Deposit'} value={cents(order.depositCents)} />
        <Amount label={order.paymentMethod === 'CASH' ? 'Balance (cash)' : 'Balance'} value={cents(order.balanceCents)} />
      </dl>
      <p className="mt-2 text-xs text-muted">
        {order.paymentMethod === 'CASH'
          ? kind === 'sales'
            ? `Paid in cash: you received ${cents(order.balanceCents)} at the meetup. The platform holds your ${cents(order.depositCents)} deposit${order.payoutStatus === 'PENDING' ? ' (payout pending)' : ''}.`
            : `Paid in cash: ${cents(order.balanceCents)} to the seller at the meetup, plus your ${cents(order.depositCents)} deposit.`
          : <>{copy.money} {cents(order.itemPriceCents)} in total{kind === 'sales' && order.status === 'COMPLETED' && order.payoutStatus === 'PENDING' ? ' (payout pending)' : ''}.</>}
      </p>
      <Progress order={order} kind={kind} />
    </li>
  )
}

/** What happens next on this order, and (for the buyer) the buttons to confirm receipt or report a problem. */
function Progress({ order, kind }: { order: Order; kind: OrderKind }) {
  const queryClient = useQueryClient()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [reportOpen, setReportOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState('')

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['orders'] })
  const confirm = useMutation({
    mutationFn: () => confirmReceipt(order.id),
    onSuccess: () => { toast.success('Thanks, order complete'); setConfirmOpen(false); refresh() },
    onError: (e) => { toast.error(parseApiError(e).message); setConfirmOpen(false) },
  })
  const report = useMutation({
    mutationFn: () => reportProblem(order.id, reason.trim()),
    onSuccess: () => { toast.success('Problem reported', { description: 'An admin will look into it.' }); setReportOpen(false); setReason(''); refresh() },
    onError: (e) => { const p = parseApiError(e); setReasonError(p.fieldErrors.reason ?? p.message) },
  })

  if (order.status === 'CANCELLED') return <Note tone="danger">This order was cancelled.</Note>
  if (order.status === 'COMPLETED') {
    return <Note tone="success">Completed{order.completedAt ? ` on ${formatDate(order.completedAt)}` : ''}.</Note>
  }
  if (order.problemReportedAt) {
    return <Note tone="warning">Problem reported on {formatDate(order.problemReportedAt)}. An admin is looking into it, and nothing completes automatically meanwhile.</Note>
  }
  if (kind === 'sales') {
    return <Note tone="neutral">Waiting for the buyer to confirm they received it{order.autoCompleteAt ? `. Completes automatically on ${formatDateTime(order.autoCompleteAt)} otherwise` : ''}.</Note>
  }
  return (
    <div className="mt-3 border-t border-line pt-3">
      <p className="text-sm text-muted">
        Got the item? Confirm it. {order.autoCompleteAt && <>Otherwise this completes automatically on {formatDateTime(order.autoCompleteAt)}, unless you report a problem.</>}
      </p>
      <div className="mt-2 flex flex-wrap gap-2">
        <Button size="sm" onClick={() => setConfirmOpen(true)}>Confirm I received it</Button>
        <Button size="sm" variant="secondary" onClick={() => { setReasonError(''); setReportOpen(true) }}>Report a problem</Button>
      </div>
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={(open) => !open && !confirm.isPending && setConfirmOpen(false)}
        title="Confirm you received it?"
        description="This completes the order and releases the seller's payment. You can't undo it."
        confirmLabel="Yes, I received it"
        loading={confirm.isPending}
        onConfirm={() => confirm.mutate()}
      />
      <Dialog open={reportOpen} onOpenChange={setReportOpen} title="Report a problem" description="Describe what went wrong. The order is put on hold until an admin reviews it.">
        <form onSubmit={(e) => { e.preventDefault(); setReasonError(''); report.mutate() }} className="space-y-4">
          <Field label="What happened?" error={reasonError}>
            <Textarea rows={4} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setReportOpen(false)}>Cancel</Button>
            <Button type="submit" variant="danger" loading={report.isPending} disabled={!reason.trim()}>Send report</Button>
          </div>
        </form>
      </Dialog>
    </div>
  )
}

function Note({ tone, children }: { tone: 'success' | 'warning' | 'danger' | 'neutral'; children: React.ReactNode }) {
  const styles = { success: 'bg-success-soft text-success', warning: 'bg-warning-soft text-warning', danger: 'bg-danger-soft text-danger', neutral: 'bg-surface text-muted' }[tone]
  return <p className={`mt-3 rounded-lg px-3 py-2 text-sm ${styles}`}>{children}</p>
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
  if (order.status === 'PAID') {
    if (order.problemReportedAt) return <Badge tone="warning">Problem reported</Badge>
    return <Badge tone="neutral">{kind === 'purchases' ? 'Awaiting your confirmation' : 'Awaiting buyer'}</Badge>
  }
  if (kind === 'sales') {
    return order.payoutStatus === 'PAID_OUT' ? <Badge tone="success">Paid out</Badge> : <Badge tone="warning">Payout pending</Badge>
  }
  return <Badge tone="success">Completed</Badge>
}
