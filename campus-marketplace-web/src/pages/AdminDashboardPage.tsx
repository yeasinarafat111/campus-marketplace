import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { REPORT_REASON_LABELS, adminDeleteListing, deactivateUser, listOrders, listReports, listUsers, markPaidOut, reactivateUser, resolveProblem, resolveReport, type AdminOrder, type AdminReport, type AdminUser, type ReportAction } from '../api/admin'
import { parseApiError } from '../api/errors'
import { searchListings, type Listing } from '../api/listings'
import Alert from '../components/Alert'
import Pagination from '../components/Pagination'
import Badge from '../components/ui/Badge'
import Button from '../components/ui/Button'
import { ConfirmDialog } from '../components/ui/Dialog'
import PriceTag from '../components/ui/PriceTag'
import { useAuth } from '../hooks/useAuth'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { formatCents, formatDate } from '../lib'

const th = 'px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted'
const td = 'px-4 py-3 text-sm'

type Pending =
  | { kind: 'deactivate'; user: AdminUser }
  | { kind: 'delete'; listing: Listing }
  | { kind: 'report'; report: AdminReport; action: Exclude<ReportAction, 'RESOLVE'> }

export default function AdminDashboardPage() {
  useDocumentTitle('Admin dashboard')
  const { user: me } = useAuth()
  const queryClient = useQueryClient()
  const [userPage, setUserPage] = useState(0)
  const [listingPage, setListingPage] = useState(0)
  const [pending, setPending] = useState<Pending | null>(null)

  const users = useQuery({ queryKey: ['admin', 'users', userPage], queryFn: () => listUsers({ page: userPage, size: 10 }) })
  // The backend has no admin-wide listing query; the public search (active listings) is what's available.
  const listings = useQuery({ queryKey: ['admin', 'listings', listingPage], queryFn: () => searchListings({ page: listingPage, size: 10 }) })

  const onError = (e: unknown) => toast.error(parseApiError(e).message)

  const toggleUser = useMutation({
    mutationFn: ({ user, active }: { user: AdminUser; active: boolean }) => (active ? deactivateUser(user.id) : reactivateUser(user.id)),
    onSuccess: (_r, { user, active }) => {
      toast.success(`${user.email} ${active ? 'deactivated' : 'reactivated'}`)
      queryClient.invalidateQueries({ queryKey: ['admin', 'users'] })
      queryClient.invalidateQueries({ queryKey: ['listings'] })
    },
    onError,
    onSettled: () => setPending(null),
  })

  const removeListing = useMutation({
    mutationFn: (listing: Listing) => adminDeleteListing(listing.id),
    onSuccess: (_r, listing) => {
      toast.success(`“${listing.title}” deleted`)
      queryClient.invalidateQueries({ queryKey: ['admin', 'listings'] })
      queryClient.invalidateQueries({ queryKey: ['listings'] })
    },
    onError,
    onSettled: () => setPending(null),
  })

  const [orderPage, setOrderPage] = useState(0)
  const problems = useQuery({ queryKey: ['admin', 'orders', 'reported'], queryFn: () => listOrders({ reported: true, size: 20 }) })
  const orders = useQuery({ queryKey: ['admin', 'orders', 'all', orderPage], queryFn: () => listOrders({ page: orderPage, size: 10 }) })
  const refreshOrders = () => queryClient.invalidateQueries({ queryKey: ['admin', 'orders'] })
  const payout = useMutation({
    mutationFn: (o: AdminOrder) => markPaidOut(o.id),
    onSuccess: (_r, o) => { toast.success(`Marked paid out: ${o.listingTitle}`); refreshOrders() },
    onError,
  })
  const resolve = useMutation({
    mutationFn: ({ order, outcome }: { order: AdminOrder; outcome: 'COMPLETE' | 'CANCEL' }) => resolveProblem(order.id, outcome),
    onSuccess: (_r, { order, outcome }) => {
      toast.success(outcome === 'COMPLETE' ? `Completed: ${order.listingTitle}` : `Cancelled: ${order.listingTitle}`)
      refreshOrders()
    },
    onError,
  })

  const reports = useQuery({ queryKey: ['admin', 'reports'], queryFn: () => listReports({ status: 'OPEN', size: 20 }) })
  const closeReport = useMutation({
    mutationFn: ({ report, action }: { report: AdminReport; action: ReportAction }) => resolveReport(report.id, action),
    onSuccess: (_r, { action }) => {
      toast.success(action === 'DELETE_LISTING' ? 'Listing deleted' : action === 'DEACTIVATE_USER' ? 'User deactivated' : 'Report resolved')
      queryClient.invalidateQueries({ queryKey: ['admin'] })
      queryClient.invalidateQueries({ queryKey: ['listings'] })
    },
    onError,
    onSettled: () => setPending(null),
  })

  const busy = toggleUser.isPending || removeListing.isPending || closeReport.isPending

  return (
    <div className="space-y-10">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight text-navy">Admin dashboard</h1>
        <Link to="/admin/revenue" className="text-sm font-semibold text-navy underline-offset-2 hover:underline">Revenue report</Link>
      </div>
      {(users.error || listings.error) && <Alert kind="error">{parseApiError(users.error ?? listings.error).message}</Alert>}

      <section aria-labelledby="reports-heading">
        <h2 id="reports-heading" className="mb-3 text-lg font-bold text-navy">
          Reported listings {reports.data && reports.data.totalElements > 0 && <Badge tone="warning">{reports.data.totalElements}</Badge>}
        </h2>
        {reports.data && reports.data.content.length === 0 && <p className="rounded-xl bg-surface px-4 py-6 text-sm text-muted">No open reports.</p>}
        <ul className="space-y-3">
          {reports.data?.content.map((r) => (
            <li key={r.id} className="rounded-xl border border-warning-soft bg-white p-4">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <p className="font-semibold text-ink">
                  {r.listing ? <Link to={`/listings/${r.listing.id}`} className="underline-offset-2 hover:underline">{r.listingTitle}</Link> : r.listingTitle}
                  <span className="ml-2 font-normal text-muted">· {REPORT_REASON_LABELS[r.reason]}</span>
                </p>
                <span className="text-xs text-muted">{formatDate(r.createdAt)}</span>
              </div>
              {r.details && <p className="mt-2 rounded-lg bg-surface p-3 text-sm text-ink">“{r.details}”</p>}
              <p className="mt-2 text-sm text-muted">
                Reported by {r.reporter.fullName}. Seller: {r.reportedUser ? r.reportedUser.fullName : 'account removed'}
                {r.reportedUser && !r.reportedUser.active && ' (deactivated)'}
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button size="sm" variant="secondary" loading={closeReport.isPending} onClick={() => closeReport.mutate({ report: r, action: 'RESOLVE' })}>Resolve, no action</Button>
                {r.listing && <Button size="sm" variant="danger" onClick={() => setPending({ kind: 'report', report: r, action: 'DELETE_LISTING' })}>Delete listing</Button>}
                {r.reportedUser?.active && <Button size="sm" variant="danger" onClick={() => setPending({ kind: 'report', report: r, action: 'DEACTIVATE_USER' })}>Deactivate seller</Button>}
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="problems-heading">
        <h2 id="problems-heading" className="mb-3 text-lg font-bold text-navy">
          Problem reports {problems.data && problems.data.totalElements > 0 && <Badge tone="warning">{problems.data.totalElements}</Badge>}
        </h2>
        {problems.data && problems.data.content.length === 0 && <p className="rounded-xl bg-surface px-4 py-6 text-sm text-muted">No open problem reports.</p>}
        <ul className="space-y-3">
          {problems.data?.content.map((o) => (
            <li key={o.id} className="rounded-xl border border-warning-soft bg-white p-4">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <p className="font-semibold text-ink">{o.listingTitle} <span className="font-normal text-muted">· {formatCents(o.itemPriceCents)}</span></p>
                <span className="text-xs text-muted">Reported {o.problemReportedAt && formatDate(o.problemReportedAt)}</span>
              </div>
              <p className="mt-2 rounded-lg bg-surface p-3 text-sm text-ink">“{o.problemReason}”</p>
              <p className="mt-2 text-sm text-muted">
                Buyer: {o.buyer.fullName} ({o.buyer.email}{o.buyer.phone && `, ${o.buyer.phone}`}) · Seller: {o.seller.fullName} ({o.seller.email}{o.seller.phone && `, ${o.seller.phone}`})
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button size="sm" loading={resolve.isPending} onClick={() => resolve.mutate({ order: o, outcome: 'COMPLETE' })}>Item arrived: complete</Button>
                <Button size="sm" variant="secondary" loading={resolve.isPending} onClick={() => resolve.mutate({ order: o, outcome: 'CANCEL' })}>Cancel the order</Button>
              </div>
            </li>
          ))}
        </ul>
        <p className="mt-2 text-xs text-muted">Cancelling doesn't refund by itself: refund the buyer in the Stripe dashboard.</p>
      </section>

      <section aria-labelledby="orders-heading">
        <h2 id="orders-heading" className="mb-3 text-lg font-bold text-navy">Orders and payouts</h2>
        <div className="relative overflow-x-auto rounded-xl border border-line">
          <table className="min-w-full divide-y divide-line">
            <thead className="bg-surface">
              <tr><th className={th}>Item</th><th className={th}>Seller is owed</th><th className={th}>Status</th><th className={th}>Payout</th><th className={th}><span className="sr-only">Actions</span></th></tr>
            </thead>
            <tbody className="divide-y divide-line">
              {orders.data?.content.map((o) => (
                <tr key={o.id}>
                  <td className={td}>{o.listingTitle}<span className="block text-xs text-muted">{o.seller.fullName} · {formatDate(o.createdAt)}</span></td>
                  <td className={`${td} tabular-nums`}>
                    {formatCents(o.owedToSellerCents)}
                    {o.paymentMethod === 'CASH' && <span className="block text-xs text-muted">deposit only (cash sale)</span>}
                  </td>
                  <td className={td}>
                    {o.status === 'COMPLETED' ? <Badge tone="success">Completed</Badge> : o.status === 'CANCELLED' ? <Badge tone="danger">Cancelled</Badge> : o.problemReportedAt ? <Badge tone="warning">Problem reported</Badge> : <Badge tone="neutral">Awaiting buyer</Badge>}
                  </td>
                  <td className={td}>{o.payoutStatus === 'PAID_OUT' ? <Badge tone="success">Paid out</Badge> : <Badge tone="muted">Pending</Badge>}</td>
                  <td className={`${td} text-right`}>
                    {o.status === 'COMPLETED' && o.payoutStatus === 'PENDING' && (
                      <Button size="sm" variant="secondary" loading={payout.isPending} onClick={() => payout.mutate(o)}>Mark paid out</Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {orders.data && orders.data.content.length === 0 && <p className="mt-2 text-sm text-muted">No orders yet.</p>}
        {orders.data && <Pagination page={orders.data.page} totalPages={orders.data.totalPages} onChange={setOrderPage} />}
        <p className="mt-2 text-xs text-muted">A seller can be paid out only once the order is completed (the buyer confirmed, or 72 hours passed with no problem report).</p>
      </section>

      <section aria-labelledby="users-heading">
        <h2 id="users-heading" className="mb-3 text-lg font-bold text-navy">Users</h2>
        <div className="relative overflow-x-auto rounded-xl border border-line">
          <table className="min-w-full divide-y divide-line">
            <thead className="bg-surface">
              <tr><th className={th}>Name</th><th className={th}>Email</th><th className={th}>Role</th><th className={th}>Status</th><th className={th}><span className="sr-only">Actions</span></th></tr>
            </thead>
            <tbody className="divide-y divide-line">
              {users.data?.content.map((u) => (
                <tr key={u.id}>
                  <td className={td}>{u.fullName}</td>
                  <td className={td}>{u.email}</td>
                  <td className={td}>{u.role}</td>
                  <td className={td}>
                    {!u.active ? <Badge tone="danger">Deactivated</Badge> : !u.enabled ? <Badge tone="warning">Unverified</Badge> : <Badge tone="success">Active</Badge>}
                  </td>
                  <td className={`${td} text-right`}>
                    {u.id !== me?.id &&
                      (u.active ? (
                        <Button variant="ghost" size="sm" className="text-danger! hover:bg-danger-soft" onClick={() => setPending({ kind: 'deactivate', user: u })}>
                          Deactivate
                        </Button>
                      ) : (
                        <Button variant="secondary" size="sm" onClick={() => toggleUser.mutate({ user: u, active: false })}>
                          Reactivate
                        </Button>
                      ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {users.data && <Pagination page={users.data.page} totalPages={users.data.totalPages} onChange={setUserPage} />}
      </section>

      <section aria-labelledby="listings-heading">
        <h2 id="listings-heading" className="mb-3 text-lg font-bold text-navy">Listings</h2>
        <div className="relative overflow-x-auto rounded-xl border border-line">
          <table className="min-w-full divide-y divide-line">
            <thead className="bg-surface">
              <tr><th className={th}>Title</th><th className={th}>Price</th><th className={th}>Seller</th><th className={th}><span className="sr-only">Actions</span></th></tr>
            </thead>
            <tbody className="divide-y divide-line">
              {listings.data?.content.map((l) => (
                <tr key={l.id}>
                  <td className={td}><Link to={`/listings/${l.id}`} className="font-medium text-navy hover:underline">{l.title}</Link></td>
                  <td className={td}><PriceTag price={l.price} size="sm" className="font-medium" /></td>
                  <td className={td}>{l.owner.fullName}</td>
                  <td className={`${td} text-right`}>
                    <Button variant="ghost" size="sm" className="text-danger! hover:bg-danger-soft" onClick={() => setPending({ kind: 'delete', listing: l })}>
                      Delete
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {listings.data && <Pagination page={listings.data.page} totalPages={listings.data.totalPages} onChange={setListingPage} />}
      </section>

      <ConfirmDialog
        open={pending !== null}
        onOpenChange={(open) => !open && !busy && setPending(null)}
        title={confirmTitle(pending)}
        description={confirmText(pending)}
        confirmLabel={pending?.kind === 'delete' || (pending?.kind === 'report' && pending.action === 'DELETE_LISTING') ? 'Delete' : 'Deactivate'}
        danger
        loading={busy}
        onConfirm={() => {
          if (pending?.kind === 'delete') removeListing.mutate(pending.listing)
          else if (pending?.kind === 'report') closeReport.mutate({ report: pending.report, action: pending.action })
          else if (pending) toggleUser.mutate({ user: pending.user, active: true })
        }}
      />
    </div>
  )
}

function confirmTitle(p: Pending | null) {
  if (p?.kind === 'delete' || (p?.kind === 'report' && p.action === 'DELETE_LISTING')) return 'Delete this listing?'
  return 'Deactivate this user?'
}

function confirmText(p: Pending | null) {
  if (!p) return ''
  if (p.kind === 'delete') return `“${p.listing.title}” will be removed for good.`
  if (p.kind === 'report') {
    return p.action === 'DELETE_LISTING'
      ? `“${p.report.listingTitle}” will be removed for good and the open reports about it closed.`
      : `${p.report.reportedUser?.fullName ?? 'This seller'} will be logged out and their listings hidden. Their other open reports are closed.`
  }
  return `${p.user.email} will be logged out and their listings hidden.`
}
