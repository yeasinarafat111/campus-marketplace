import { Star } from 'lucide-react'
import { cx } from '../lib'

/** Read-only stars: filled up to the rounded value. */
export function Stars({ value, className }: { value: number; className?: string }) {
  const filled = Math.round(value)
  return (
    <span className={cx('inline-flex items-center gap-0.5', className)} role="img" aria-label={`${value.toFixed(1).replace(/\.0$/, '')} out of 5 stars`}>
      {[1, 2, 3, 4, 5].map((n) => (
        <Star key={n} aria-hidden className={cx('size-4', n <= filled ? 'fill-amber-500 text-amber-500' : 'text-gray-300')} />
      ))}
    </span>
  )
}

/** Pick 1-5. Each star is a 44px radio-style button. */
export function StarInput({ value, onChange }: { value: number; onChange: (stars: number) => void }) {
  return (
    <div role="radiogroup" aria-label="Rating" className="flex gap-1">
      {[1, 2, 3, 4, 5].map((n) => (
        <button key={n} type="button" role="radio" aria-checked={value === n} aria-label={`${n} star${n === 1 ? '' : 's'}`}
          onClick={() => onChange(n)} className="flex size-11 items-center justify-center rounded-lg hover:bg-surface">
          <Star aria-hidden className={cx('size-7', n <= value ? 'fill-amber-500 text-amber-500' : 'text-gray-300')} />
        </button>
      ))}
    </div>
  )
}
