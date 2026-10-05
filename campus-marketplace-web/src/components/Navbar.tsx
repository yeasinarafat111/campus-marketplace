import { Link, NavLink } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `border-b-2 py-1 text-base font-medium ${
    isActive ? 'border-gold text-white' : 'border-transparent text-white/75 hover:text-white'
  }`

export default function Navbar() {
  const { user, isAuthenticated, logout } = useAuth()
  return (
    <header className="on-navy border-b-4 border-gold bg-navy text-white">
      <nav className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
        <Link to="/listings" className="mr-2 font-display text-2xl font-bold tracking-wide text-white">
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
            <NavLink to="/profile" className={linkClass}>
              Profile
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
            <button onClick={logout} className="text-white/80 underline underline-offset-2 hover:text-white">
              Log out
              {user && <span className="hidden text-white/60 sm:inline"> ({user.email})</span>}
            </button>
          ) : (
            <Link to="/login" className="btn btn-gold btn-sm">
              Log in
            </Link>
          )}
        </div>
      </nav>
    </header>
  )
}
