import { X } from 'lucide-react'
import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'

const dismissedKey = (userId: number) => `campus.phoneBannerDismissed.${userId}`

const wasDismissed = (userId: number) => {
  try {
    return sessionStorage.getItem(dismissedKey(userId)) === '1'
  } catch {
    return false
  }
}

/**
 * Shown to accounts created before phone numbers were collected. Dismissing hides it for this browser
 * session only, so it comes back until a phone is added.
 */
export default function PhoneBanner() {
  const { user } = useAuth()
  const { pathname } = useLocation()
  const [dismissedFor, setDismissedFor] = useState<number | null>(null)
  if (!user || user.phone || pathname === '/profile') return null
  if (dismissedFor === user.id || wasDismissed(user.id)) return null

  const dismiss = () => {
    try {
      sessionStorage.setItem(dismissedKey(user.id), '1')
    } catch {
      // Storage unavailable: the in-memory state below still hides it until the next reload.
    }
    setDismissedFor(user.id)
  }

  return (
    <div role="status" className="border-b border-warning-soft bg-warning-soft">
      <div className="mx-auto flex max-w-7xl items-center gap-3 px-4 py-2.5 text-sm text-warning">
        <p className="flex-1">
          Add your phone number so buyers can reach you.{' '}
          <Link to="/profile" className="font-semibold underline underline-offset-2">
            Go to your profile
          </Link>
        </p>
        <button type="button" onClick={dismiss} aria-label="Dismiss" className="rounded-md p-1 transition-colors hover:bg-black/5">
          <X className="size-4" aria-hidden />
        </button>
      </div>
    </div>
  )
}
