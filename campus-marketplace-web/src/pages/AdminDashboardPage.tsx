import { useState } from 'react'
import { Link } from 'react-router-dom'
import { adminDeleteListing, deactivateUser, listUsers, reactivateUser } from '../api/admin'
import { parseApiError } from '../api/errors'
import { searchListings } from '../api/listings'
import Alert from '../components/Alert'
import Pagination from '../components/Pagination'
import { useAuth } from '../hooks/useAuth'
import { useFetch } from '../hooks/useFetch'
import { formatPrice } from '../lib'

export default function AdminDashboardPage() {
  const { user: me } = useAuth()
  const [userPage, setUserPage] = useState(0)
  const [listingPage, setListingPage] = useState(0)
  const users = useFetch(() => listUsers({ page: userPage, size: 10 }), [userPage])
  // The backend has no admin-wide listing query; the public search (active listings) is what's available.
  const listings = useFetch(() => searchListings({ page: listingPage, size: 10 }), [listingPage])
  const [actionError, setActionError] = useState('')

  async function run(action: () => Promise<unknown>, reload: () => void) {
    setActionError('')
    try {
      await action()
      reload()
    } catch (e) {
      setActionError(parseApiError(e).message)
    }
  }

  const th = 'px-3 py-2 text-left text-xs font-semibold uppercase text-gray-500'
  const td = 'px-3 py-2 text-sm'

  return (
    <div className="space-y-10">
      <h1 className="text-2xl font-bold">Admin dashboard</h1>
      {(actionError || users.error || listings.error) && (
        <Alert kind="error">{actionError || users.error || listings.error}</Alert>
      )}

      <section>
        <h2 className="mb-3 text-lg font-semibold">Users</h2>
        <div className="overflow-x-auto rounded-xl bg-white shadow-sm ring-1 ring-gray-200">
          <table className="min-w-full divide-y divide-gray-200">
            <thead>
              <tr><th className={th}>Name</th><th className={th}>Email</th><th className={th}>Role</th><th className={th}>Status</th><th className={th}></th></tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {users.data?.content.map((u) => (
                <tr key={u.id}>
                  <td className={td}>{u.fullName}</td>
                  <td className={td}>{u.email}</td>
                  <td className={td}>{u.role}</td>
                  <td className={td}>
                    {!u.active ? <span className="text-red-600">Deactivated</span> : !u.enabled ? <span className="text-amber-600">Unverified</span> : 'Active'}
                  </td>
                  <td className={`${td} text-right`}>
                    {u.id !== me?.id &&
                      (u.active ? (
                        <button className="text-red-600 hover:underline"
                          onClick={() => window.confirm(`Deactivate ${u.email}?`) && run(() => deactivateUser(u.id), users.reload)}>
                          Deactivate
                        </button>
                      ) : (
                        <button className="text-indigo-600 hover:underline" onClick={() => run(() => reactivateUser(u.id), users.reload)}>
                          Reactivate
                        </button>
                      ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {users.data && <Pagination page={users.data.page} totalPages={users.data.totalPages} onChange={setUserPage} />}
      </section>

      <section>
        <h2 className="mb-3 text-lg font-semibold">Listings</h2>
        <div className="overflow-x-auto rounded-xl bg-white shadow-sm ring-1 ring-gray-200">
          <table className="min-w-full divide-y divide-gray-200">
            <thead>
              <tr><th className={th}>Title</th><th className={th}>Price</th><th className={th}>Seller</th><th className={th}></th></tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {listings.data?.content.map((l) => (
                <tr key={l.id}>
                  <td className={td}><Link to={`/listings/${l.id}`} className="hover:underline">{l.title}</Link></td>
                  <td className={td}>{formatPrice(l.price)}</td>
                  <td className={td}>{l.owner.fullName}</td>
                  <td className={`${td} text-right`}>
                    <button className="text-red-600 hover:underline"
                      onClick={() => window.confirm(`Delete "${l.title}"?`) && run(() => adminDeleteListing(l.id), listings.reload)}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {listings.data && <Pagination page={listings.data.page} totalPages={listings.data.totalPages} onChange={setListingPage} />}
      </section>
    </div>
  )
}
