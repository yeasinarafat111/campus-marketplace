import { useRef, useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import type { Category, ListingPhoto } from '../api/listings'
import ImageWithFallback from './ui/ImageWithFallback'
import { cx } from '../lib'

/** Big photo with thumbnails; swipe on touch screens, arrow buttons and keys elsewhere. */
export function PhotoGallery({ photos, title, category }: { photos: ListingPhoto[]; title: string; category: Category }) {
  const [index, setIndex] = useState(0)
  const startX = useRef<number | null>(null)
  const count = photos.length
  const current = Math.min(index, Math.max(count - 1, 0))

  const go = (i: number) => setIndex((i + count) % count)

  if (count === 0) {
    return <ImageWithFallback src={null} alt={title} category={category} className="aspect-square max-h-[640px] w-full rounded-xl sm:aspect-[4/3]" />
  }

  return (
    <div>
      <div
        className="relative touch-pan-y select-none"
        tabIndex={0}
        role="group"
        aria-roledescription="carousel"
        aria-label={`Photos of ${title}`}
        onKeyDown={(e) => {
          if (count < 2) return
          if (e.key === 'ArrowLeft') go(current - 1)
          if (e.key === 'ArrowRight') go(current + 1)
        }}
        onTouchStart={(e) => { startX.current = e.touches[0].clientX }}
        onTouchEnd={(e) => {
          if (startX.current === null || count < 2) return
          const dx = e.changedTouches[0].clientX - startX.current
          startX.current = null
          if (Math.abs(dx) > 40) go(dx < 0 ? current + 1 : current - 1)
        }}
      >
        <ImageWithFallback
          src={photos[current].url}
          alt={`${title}, photo ${current + 1} of ${count}`}
          category={category}
          className="aspect-square max-h-[640px] w-full rounded-xl sm:aspect-[4/3]"
        />
        {count > 1 && (
          <>
            <button type="button" aria-label="Previous photo" onClick={() => go(current - 1)}
              className="absolute left-2 top-1/2 hidden size-10 -translate-y-1/2 items-center justify-center rounded-full bg-white/90 text-ink shadow sm:flex">
              <ChevronLeft className="size-5" aria-hidden />
            </button>
            <button type="button" aria-label="Next photo" onClick={() => go(current + 1)}
              className="absolute right-2 top-1/2 hidden size-10 -translate-y-1/2 items-center justify-center rounded-full bg-white/90 text-ink shadow sm:flex">
              <ChevronRight className="size-5" aria-hidden />
            </button>
            <span className="absolute bottom-2 right-2 rounded-full bg-black/60 px-2 py-0.5 text-xs font-medium text-white tabular-nums">
              {current + 1} / {count}
            </span>
          </>
        )}
      </div>
      {count > 1 && (
        <ul className="mt-2 flex gap-2 overflow-x-auto pb-1">
          {photos.map((p, i) => (
            <li key={p.id} className="shrink-0">
              <button type="button" onClick={() => setIndex(i)} aria-label={`Show photo ${i + 1}`} aria-current={i === current}
                className={cx('block size-16 overflow-hidden rounded-lg border-2 sm:size-20', i === current ? 'border-navy' : 'border-transparent opacity-70 hover:opacity-100')}>
                <img src={p.url} alt="" loading="lazy" className="size-full object-cover" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
