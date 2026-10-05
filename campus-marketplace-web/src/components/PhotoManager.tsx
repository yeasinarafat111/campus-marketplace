import { useRef } from 'react'
import { ArrowLeft, ArrowRight, ImagePlus, X } from 'lucide-react'
import { MAX_PHOTOS } from '../api/listings'
import { cx } from '../lib'

export interface PhotoItem {
  key: string | number
  url: string
}

const MAX_IMAGE_BYTES = 5 * 1024 * 1024

/** Thumbnails with move-left / move-right / remove, plus an add button. Pure UI: the parent decides what each action does. */
export function PhotoManager({
  items,
  busy,
  onAdd,
  onRemove,
  onMove,
  onError,
}: {
  items: PhotoItem[]
  busy?: boolean
  onAdd: (files: File[]) => void
  onRemove: (index: number) => void
  onMove: (from: number, to: number) => void
  onError: (message: string) => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const room = MAX_PHOTOS - items.length

  function accept(list: FileList | null) {
    if (!list || list.length === 0) return
    const files = Array.from(list)
    if (files.some((f) => !f.type.startsWith('image/'))) return onError('Please choose image files.')
    if (files.some((f) => f.size > MAX_IMAGE_BYTES)) return onError('Each image must be 5 MB or smaller.')
    if (files.length > room) onError(`You can add ${room} more photo${room === 1 ? '' : 's'}; the rest were skipped.`)
    else onError('')
    onAdd(files.slice(0, room))
    if (inputRef.current) inputRef.current.value = ''
  }

  return (
    <div>
      <p id="photo-label" className="mb-1.5 text-sm font-medium text-ink">
        Photos <span className="font-normal text-muted">(optional, up to {MAX_PHOTOS}; the first is the cover)</span>
      </p>
      <ul className="grid grid-cols-3 gap-2 sm:grid-cols-5">
        {items.map((item, i) => (
          <li key={item.key} className="relative">
            <img src={item.url} alt={`Photo ${i + 1}`} className={cx('aspect-square w-full rounded-lg object-cover', busy && 'opacity-60')} />
            {i === 0 && <span className="absolute left-1 top-1 rounded bg-navy px-1.5 py-0.5 text-[10px] font-semibold text-white">Cover</span>}
            <button type="button" disabled={busy} onClick={() => onRemove(i)} aria-label={`Remove photo ${i + 1}`}
              className="absolute right-1 top-1 flex size-8 items-center justify-center rounded-full bg-white text-ink shadow">
              <X className="size-4" aria-hidden />
            </button>
            <div className="absolute inset-x-1 bottom-1 flex justify-between">
              <button type="button" disabled={busy || i === 0} onClick={() => onMove(i, i - 1)} aria-label={`Move photo ${i + 1} earlier`}
                className="flex size-8 items-center justify-center rounded-full bg-white text-ink shadow disabled:invisible">
                <ArrowLeft className="size-4" aria-hidden />
              </button>
              <button type="button" disabled={busy || i === items.length - 1} onClick={() => onMove(i, i + 1)} aria-label={`Move photo ${i + 1} later`}
                className="flex size-8 items-center justify-center rounded-full bg-white text-ink shadow disabled:invisible">
                <ArrowRight className="size-4" aria-hidden />
              </button>
            </div>
          </li>
        ))}
        {room > 0 && (
          <li>
            <label className={cx('flex aspect-square cursor-pointer flex-col items-center justify-center gap-1 rounded-lg border-2 border-dashed border-line bg-surface text-center text-xs font-semibold text-ink hover:border-gray-300 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-navy', busy && 'pointer-events-none opacity-60')}>
              <ImagePlus className="size-6 text-muted" aria-hidden />
              Add photo
              <input ref={inputRef} type="file" accept="image/*" multiple aria-labelledby="photo-label" className="sr-only"
                onChange={(e) => accept(e.target.files)} />
            </label>
          </li>
        )}
      </ul>
    </div>
  )
}
