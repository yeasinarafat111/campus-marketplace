import { Heart, LayoutDashboard, List, LogOut, Menu, MessageCircle, Plus, Receipt, Search, ShoppingBag, User as UserIcon } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { chatKeys, getUnreadCount } from '../api/chat'
import { useAuth } from '../hooks/useAuth'
import Logo from './Logo'
import NotificationBell from './NotificationBell'
import Avatar from './ui/Avatar'
import { buttonClasses } from './ui/buttonClasses'
import Dialog from './ui/Dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuLink,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from './ui/DropdownMenu'
import { Input } from './ui/Input'

function SearchBox({ className, autoFocus = false, onDone }: { className?: string; autoFocus?: boolean; onDone?: () => void }) {
  const navigate = useNavigate()
  const location = useLocation()
  const initial = location.pathname === '/listings' ? new URLSearchParams(location.search).get('q') ?? '' : ''
  const [q, setQ] = useState(initial)

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    const query = q.trim()
    navigate(query ? `/listings?q=${encodeURIComponent(query)}` : '/listings')
    onDone?.()
  }

  return (
    <form onSubmit={onSubmit} role="search" className={className}>
      <div className="relative">
        <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted" />
        <Input
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Search textbooks, laptops, furniture…"
          aria-label="Search listings"
          autoFocus={autoFocus}
          className="h-10 border-transparent bg-white pl-9"
        />
      </div>
    </form>
  )
}

/** The messages icon with the unread count. Kept live by the chat socket (see ChatSocketProvider). */
function MessagesLink() {
  const unread = useQuery({ queryKey: chatKeys.unread, queryFn: getUnreadCount, staleTime: 60_000 })
  const count = unread.data?.count ?? 0
  return (
    <Link to="/messages" aria-label={count > 0 ? `Messages, ${count} unread` : 'Messages'} className="relative rounded-lg p-2 transition-colors hover:bg-white/10">
      <MessageCircle className="size-5" aria-hidden />
      {count > 0 && (
        <span className="absolute -right-0.5 -top-0.5 flex min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold leading-4 text-white">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </Link>
  )
}

function UserMenu() {
  const { user, logout } = useAuth()
  if (!user) return null
  const firstName = user.fullName.trim().split(/\s+/)[0]
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label={`Account menu for ${user.fullName}`}
        className="flex items-center gap-2 rounded-full py-1 pl-1 pr-1 text-white transition-colors hover:bg-white/10 sm:pr-3"
      >
        <Avatar name={user.fullName} size="md" className="ring-2 ring-white/30" />
        <span className="hidden text-sm font-semibold sm:inline">{firstName}</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        <DropdownMenuLabel>
          <p className="truncate font-semibold text-ink">{user.fullName}</p>
          <p className="truncate text-xs text-muted">{user.email}</p>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuLink to="/my-listings" icon={<List className="size-4 text-muted" aria-hidden />}>
          My listings
        </DropdownMenuLink>
        <DropdownMenuLink to="/saved" icon={<Heart className="size-4 text-muted" aria-hidden />}>
          Saved
        </DropdownMenuLink>
        <DropdownMenuLink to="/messages" icon={<MessageCircle className="size-4 text-muted" aria-hidden />}>
          Messages
        </DropdownMenuLink>
        <DropdownMenuLink to="/purchases" icon={<ShoppingBag className="size-4 text-muted" aria-hidden />}>
          Purchases
        </DropdownMenuLink>
        <DropdownMenuLink to="/sales" icon={<Receipt className="size-4 text-muted" aria-hidden />}>
          Sales
        </DropdownMenuLink>
        <DropdownMenuLink to="/profile" icon={<UserIcon className="size-4 text-muted" aria-hidden />}>
          Profile
        </DropdownMenuLink>
        {user.role === 'ADMIN' && (
          <>
            <DropdownMenuLink to="/admin" icon={<LayoutDashboard className="size-4 text-muted" aria-hidden />}>
              Admin
            </DropdownMenuLink>
            <DropdownMenuLink to="/admin/revenue" icon={<LayoutDashboard className="size-4 text-muted" aria-hidden />}>
              Revenue
            </DropdownMenuLink>
          </>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={logout} icon={<LogOut className="size-4 text-muted" aria-hidden />}>
          Log out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

export default function Header() {
  const { user, isAuthenticated, isLoading } = useAuth()
  const [navOpen, setNavOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const closeNav = () => setNavOpen(false)

  return (
    <header className="on-navy sticky top-0 z-40 bg-navy text-white">
      <div className="mx-auto flex h-16 max-w-7xl items-center gap-1.5 px-4 sm:gap-3">
        <button
          type="button"
          onClick={() => setNavOpen(true)}
          aria-label="Open menu"
          className="-ml-2 rounded-lg p-2 transition-colors hover:bg-white/10 md:hidden"
        >
          <Menu className="size-5" aria-hidden />
        </button>
        <Logo onDark />

        <SearchBox className="mx-2 hidden max-w-xl flex-1 md:block" />
        <div className="flex-1 md:hidden" />

        <button
          type="button"
          onClick={() => setSearchOpen((v) => !v)}
          aria-label="Search"
          aria-expanded={searchOpen}
          className="rounded-lg p-2 transition-colors hover:bg-white/10 md:hidden"
        >
          <Search className="size-5" aria-hidden />
        </button>

        <div className="hidden sm:block">
          <Link to="/listings/new" className={buttonClasses({ variant: 'secondary', className: 'border-transparent' })}>
            <Plus className="size-4" aria-hidden />
            Sell
          </Link>
        </div>

        {isAuthenticated && <NotificationBell />}
        {isAuthenticated && <MessagesLink />}

        {isAuthenticated ? (
          <UserMenu />
        ) : (
          !isLoading && (
            <div className="flex items-center gap-1">
              <Link to="/login" className={buttonClasses({ variant: 'ghost', className: 'text-white hover:bg-white/10' })}>
                Log in
              </Link>
              <div className="hidden sm:block">
                <Link to="/signup" className={buttonClasses({ variant: 'secondary', className: 'border-transparent' })}>
                  Sign up
                </Link>
              </div>
            </div>
          )
        )}
      </div>

      {searchOpen && (
        <div className="border-t border-white/10 px-4 py-3 md:hidden">
          <SearchBox autoFocus onDone={() => setSearchOpen(false)} />
        </div>
      )}

      <Dialog open={navOpen} onOpenChange={setNavOpen} title="Menu" side="left">
        <nav aria-label="Main" className="-mx-2 flex flex-col text-sm font-medium">
          <MobileLink to="/listings" onClick={closeNav}>Browse</MobileLink>
          <MobileLink to="/listings/new" onClick={closeNav}>Sell something</MobileLink>
          {isAuthenticated ? (
            <>
              <MobileLink to="/my-listings" onClick={closeNav}>My listings</MobileLink>
              <MobileLink to="/saved" onClick={closeNav}>Saved</MobileLink>
              <MobileLink to="/messages" onClick={closeNav}>Messages</MobileLink>
              <MobileLink to="/purchases" onClick={closeNav}>Purchases</MobileLink>
              <MobileLink to="/sales" onClick={closeNav}>Sales</MobileLink>
              <MobileLink to="/profile" onClick={closeNav}>Profile</MobileLink>
              {user?.role === 'ADMIN' && <MobileLink to="/admin" onClick={closeNav}>Admin</MobileLink>}
              {user?.role === 'ADMIN' && <MobileLink to="/admin/revenue" onClick={closeNav}>Revenue</MobileLink>}
            </>
          ) : (
            <>
              <MobileLink to="/login" onClick={closeNav}>Log in</MobileLink>
              <MobileLink to="/signup" onClick={closeNav}>Sign up</MobileLink>
            </>
          )}
        </nav>
      </Dialog>
    </header>
  )
}

function MobileLink({ to, onClick, children }: { to: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <Link to={to} onClick={onClick} className="rounded-lg px-3 py-3 text-ink transition-colors hover:bg-surface">
      {children}
    </Link>
  )
}
