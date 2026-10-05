import { Heart } from 'lucide-react'
import { useLocation, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useSaved } from '../hooks/useSaved'
import { cx } from '../lib'

/** A heart that bookmarks a listing. Logged-out visitors are sent to log in. Not a link, so it can sit over a card. */
export default function SaveButton({ listingId, className, withLabel }: { listingId: number; className?: string; withLabel?: boolean }) {
  const { isAuthenticated, isSaved, toggle } = useSaved()
  const navigate = useNavigate()
  const location = useLocation()
  const saved = isAuthenticated && isSaved(listingId)

  return (
    <button
      type="button"
      aria-pressed={saved}
      aria-label={saved ? 'Remove from saved' : 'Save listing'}
      onClick={() => {
        if (!isAuthenticated) {
          toast('Log in to save listings')
          navigate('/login', { state: { from: location } })
          return
        }
        toggle(listingId)
      }}
      className={cx(
        'inline-flex items-center justify-center gap-1.5 rounded-full bg-white/95 text-ink shadow-sm transition-colors hover:bg-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-navy',
        withLabel ? 'h-11 px-4 text-sm font-semibold' : 'size-9',
        className,
      )}
    >
      <Heart className={cx('size-5', saved ? 'fill-danger text-danger' : 'text-ink')} aria-hidden />
      {withLabel && (saved ? 'Saved' : 'Save')}
    </button>
  )
}
