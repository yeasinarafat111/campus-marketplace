import { cx, formatPrice } from '../../lib'

const SIZES = { sm: 'text-base', md: 'text-lg', lg: 'text-3xl' }

/** Price in bold tabular figures. A price of zero reads "Free". */
export default function PriceTag({ price, size = 'md', className }: { price: number; size?: keyof typeof SIZES; className?: string }) {
  return (
    <span className={cx('font-bold tabular-nums text-ink', SIZES[size], className)}>
      {price === 0 ? 'Free' : formatPrice(price)}
    </span>
  )
}
