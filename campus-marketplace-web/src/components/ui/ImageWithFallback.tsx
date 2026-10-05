import { Package } from 'lucide-react'
import { useState } from 'react'
import type { Category } from '../../api/listings'
import { CATEGORY_ICONS } from '../categoryIcons'
import { cx } from '../../lib'

/**
 * A listing photo, or (when there is none or it fails to load) the category icon on a neutral
 * background, so a missing photo looks intentional rather than broken. Sizing comes from `className`.
 */
export default function ImageWithFallback({
  src,
  alt,
  category,
  className,
}: {
  src: string | null | undefined
  alt: string
  category?: Category
  className?: string
}) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null)
  const Icon = category ? CATEGORY_ICONS[category] : Package
  if (!src || failedSrc === src) {
    return (
      <div role="img" aria-label={alt || 'No photo'} className={cx('flex items-center justify-center bg-surface text-gray-400', className)}>
        <Icon className="size-1/4 max-h-16 min-h-6 min-w-6 max-w-16" aria-hidden />
      </div>
    )
  }
  return <img src={src} alt={alt} loading="lazy" onError={() => setFailedSrc(src)} className={cx('bg-surface object-cover', className)} />
}
