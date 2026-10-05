import { cx } from '../../lib'

export default function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cx('skeleton rounded-lg', className)} />
}

export function ListingCardSkeleton() {
  return (
    <div aria-hidden>
      <Skeleton className="aspect-square w-full rounded-xl" />
      <Skeleton className="mt-3 h-5 w-20" />
      <Skeleton className="mt-2 h-4 w-full" />
      <Skeleton className="mt-2 h-3 w-1/2" />
    </div>
  )
}
