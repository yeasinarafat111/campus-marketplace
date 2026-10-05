import { cx, initials } from '../../lib'

// Dark enough that white initials pass 4.5:1 contrast.
const COLORS = ['bg-blue-700', 'bg-emerald-700', 'bg-violet-700', 'bg-rose-700', 'bg-amber-700', 'bg-cyan-700', 'bg-fuchsia-700', 'bg-slate-700']

/** Same name, same colour, every time. */
const colorFor = (name: string) => {
  let hash = 0
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  return COLORS[hash % COLORS.length]
}

const SIZES = { sm: 'size-7 text-xs', md: 'size-9 text-sm', lg: 'size-12 text-base' }

export default function Avatar({ name, size = 'md', className }: { name: string; size?: keyof typeof SIZES; className?: string }) {
  return (
    <span
      aria-hidden
      className={cx('inline-flex shrink-0 items-center justify-center rounded-full font-semibold text-white', colorFor(name), SIZES[size], className)}
    >
      {initials(name)}
    </span>
  )
}
