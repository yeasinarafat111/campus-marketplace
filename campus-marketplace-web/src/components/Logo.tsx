import { Link } from 'react-router-dom'
import { cx } from '../lib'

/** Wordmark. `onDark` is for the navy header. */
export default function Logo({ onDark = false, className }: { onDark?: boolean; className?: string }) {
  return (
    <Link to="/listings" className={cx('flex items-center gap-2 text-lg font-bold tracking-tight', onDark ? 'text-white' : 'text-navy', className)}>
      <span aria-hidden className={cx('flex size-8 items-center justify-center rounded-lg text-sm font-bold', onDark ? 'bg-white text-navy' : 'bg-navy text-white')}>
        CM
      </span>
      <span className="hidden sm:inline">Campus Marketplace</span>
      <span className="sm:hidden">Campus</span>
    </Link>
  )
}
