import { Link, NavLink } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `text-sm font-medium ${isActive ? 'text-indigo-600' : 'text-gray-600 hover:text-gray-900'}`

export default function Navbar() {
  const { user, isAuthenticated, logout } = useAuth()
  return (
    <header className="border-b border-gray-200 bg-white">
      <nav className="mx-auto flex max-w-6xl items-center gap-6 px-4 py-3">
        <Link to="/listings" className="font-bold text-indigo-600">
          Campus Marketplace
        </Link>
        <NavLink to="/listings" end className={linkClass}>
          Browse
        </NavLink>
        {isAuthenticated && (
          <>
            <NavLink to="/my-listings" className={linkClass}>
              My listings
            </NavLink>
            <NavLink to="/listings/new" className={linkClass}>
              Sell something
            </NavLink>
          </>
        )}
        {user?.role === 'ADMIN' && (
          <NavLink to="/admin" className={linkClass}>
            Admin
          </NavLink>
        )}
        <div className="ml-auto text-sm">
          {isAuthenticated ? (
            <button onClick={logout} className="text-gray-600 hover:text-gray-900">
              Log out {user && <span className="text-gray-400">({user.email})</span>}
            </button>
          ) : (
            <Link to="/login" className="font-medium text-indigo-600 hover:underline">
              Log in
            </Link>
          )}
        </div>
      </nav>
    </header>
  )
}
