import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { adminDeleteListing, deactivateUser, listUsers, reactivateUser, type AdminUser } from '../api/admin'
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

const th = 'px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted'
const td = 'px-4 py-3 text-sm'

type Pending =
  | { kind: 'deactivate'; user: AdminUser }
  | { kind: 'delete'; listing: Listing }

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

  const busy = toggleUser.isPending || removeListing.isPending

  return (
    <div className="space-y-10">
      <h1 className="text-2xl font-bold tracking-tight text-navy">Admin dashboard</h1>
      {(users.error || listings.error) && <Alert kind="error">{parseApiError(users.error ?? listings.error).message}</Alert>}

      <section aria-labelledby="users-heading">
        <h2 id="users-heading" className="mb-3 text-lg font-bold text-navy">Users</h2>
        <div className="overflow-x-auto rounded-xl border border-line">
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
                        <Button variant="ghost" size="sm" className="text-danger hover:bg-danger-soft" onClick={() => setPending({ kind: 'deactivate', user: u })}>
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
        <div className="overflow-x-auto rounded-xl border border-line">
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
                    <Button variant="ghost" size="sm" className="text-danger hover:bg-danger-soft" onClick={() => setPending({ kind: 'delete', listing: l })}>
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
        title={pending?.kind === 'delete' ? 'Delete this listing?' : 'Deactivate this user?'}
        description={
          pending?.kind === 'delete'
            ? `“${pending.listing.title}” will be removed for good.`
            : pending ? `${pending.user.email} will be logged out and their listings hidden.` : ''
        }
        confirmLabel={pending?.kind === 'delete' ? 'Delete' : 'Deactivate'}
        danger
        loading={busy}
        onConfirm={() => {
          if (pending?.kind === 'delete') removeListing.mutate(pending.listing)
          else if (pending) toggleUser.mutate({ user: pending.user, active: true })
        }}
      />
    </div>
  )
}
