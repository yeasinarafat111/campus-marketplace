import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ImagePlus, X } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type DragEvent, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { CATEGORIES, createListing, getListing, updateListing, type Category, type Listing, type ListingInput } from '../api/listings'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import FormField from '../components/FormField'
import Button from '../components/ui/Button'
import Field from '../components/ui/Field'
import { Input, Select, Textarea } from '../components/ui/Input'
import Skeleton from '../components/ui/Skeleton'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { cx, formatCategory, formatPrice } from '../lib'
import NotFoundPage from './NotFoundPage'
import { isAxiosError } from 'axios'

const MAX_IMAGE_BYTES = 5 * 1024 * 1024 // matches the backend's multipart limit
const MAX_DESCRIPTION = 2000

const empty: ListingInput = { title: '', description: '', price: '', category: 'OTHER', imageUrl: '' }

export default function CreateEditListingPage() {
  const { id } = useParams()
  const isEdit = id !== undefined
  useDocumentTitle(isEdit ? 'Edit listing' : 'Sell something')

  const existing = useQuery({ queryKey: ['listing', id], queryFn: () => getListing(id!), enabled: isEdit })

  if (isEdit && existing.isPending) return <Skeleton className="mx-auto h-96 max-w-xl" />
  if (isEdit && isAxiosError(existing.error) && existing.error.response?.status === 404) return <NotFoundPage what="listing" />
  if (isEdit && existing.error) return <Alert kind="error">{parseApiError(existing.error).message}</Alert>
  return <ListingForm existing={existing.data} />
}

function ListingForm({ existing }: { existing?: Listing }) {
  const isEdit = existing !== undefined
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [form, setForm] = useState<ListingInput>(
    existing
      ? {
          title: existing.title,
          description: existing.description ?? '',
          price: String(existing.price),
          category: existing.category,
          imageUrl: existing.imageUrl ?? '',
        }
      : empty,
  )
  const [image, setImage] = useState<File | null>(null)
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const save = useMutation({
    mutationFn: () => (existing ? updateListing(existing.id, form) : createListing(form, image)),
    onSuccess: (saved) => {
      toast.success(isEdit ? 'Listing updated' : 'Listing created')
      queryClient.invalidateQueries({ queryKey: ['listings'] })
      queryClient.invalidateQueries({ queryKey: ['my-listings'] })
      queryClient.invalidateQueries({ queryKey: ['listing', String(saved.id)] })
      navigate(`/listings/${saved.id}`)
    },
    onError: (err) => {
      const parsed = parseApiError(err)
      setError(parsed.message)
      setFieldErrors(parsed.fieldErrors)
    },
  })

  const set = (key: keyof ListingInput) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }))

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setFieldErrors({})
    // Same rule as the backend (decision D7): free, or at least $1.00.
    if (priceValid && price > 0 && price < 1) {
      setFieldErrors({ price: 'Price must be 0 (free) or at least 1.00' })
      return
    }
    save.mutate()
  }

  const price = Number(form.price)
  const priceValid = form.price.trim() !== '' && Number.isFinite(price) && price >= 0

  return (
    <div className="mx-auto max-w-xl">
      <h1 className="mb-6 text-2xl font-bold tracking-tight text-navy">{isEdit ? 'Edit listing' : 'Sell something'}</h1>
      <form onSubmit={onSubmit} className="space-y-5" noValidate>
        {error && <Alert kind="error">{error}</Alert>}

        {isEdit ? (
          <ImageUrlField value={form.imageUrl ?? ''} category={form.category} onChange={set('imageUrl')} error={fieldErrors.imageUrl} />
        ) : (
          <ImageDropzone file={image} onChange={setImage} onError={setError} />
        )}

        <FormField label="Title" name="title" required maxLength={120} value={form.title}
          onChange={set('title')} error={fieldErrors.title} />

        <Field label="Description" error={fieldErrors.description}
          hint={<span className="tabular-nums">{form.description.length} / {MAX_DESCRIPTION}</span>}>
          <Textarea rows={5} maxLength={MAX_DESCRIPTION} value={form.description} onChange={set('description')} />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Price (USD)" error={fieldErrors.price}
            hint={priceValid ? <>Buyers will see <strong className="tabular-nums text-ink">{price === 0 ? 'Free' : formatPrice(price)}</strong>. Free, or at least $1.00.</> : 'Free, or at least $1.00.'}>
            <div className="relative">
              <span aria-hidden className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted">$</span>
              <Input name="price" type="number" inputMode="decimal" min="0" step="0.01" required className="pl-7 tabular-nums"
                value={form.price} onChange={set('price')} />
            </div>
          </Field>
          <Field label="Category" error={fieldErrors.category}>
            <Select value={form.category} onChange={set('category')}>
              {CATEGORIES.map((c: Category) => (
                <option key={c} value={c}>{formatCategory(c)}</option>
              ))}
            </Select>
          </Field>
        </div>

        <Button type="submit" size="lg" loading={save.isPending} className="w-full">
          {isEdit ? 'Save changes' : 'Post listing'}
        </Button>
      </form>
    </div>
  )
}

function ImageDropzone({ file, onChange, onError }: { file: File | null; onChange: (f: File | null) => void; onError: (m: string) => void }) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const preview = useMemo(() => (file ? URL.createObjectURL(file) : null), [file])
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview) }, [preview])

  function accept(f: File | undefined) {
    if (!f) return
    if (!f.type.startsWith('image/')) return onError('Please choose an image file.')
    if (f.size > MAX_IMAGE_BYTES) return onError('Image must be 5 MB or smaller.')
    onError('')
    onChange(f)
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    accept(e.dataTransfer.files[0])
  }

  if (preview) {
    return (
      <div>
        <p className="mb-1.5 text-sm font-medium text-ink">Photo</p>
        <div className="relative overflow-hidden rounded-xl">
          <img src={preview} alt="Preview of your photo" className="aspect-[4/3] w-full object-cover" />
          <button type="button" onClick={() => { onChange(null); if (inputRef.current) inputRef.current.value = '' }}
            aria-label="Remove photo" className="absolute right-2 top-2 rounded-full bg-white p-2 text-ink shadow transition-colors hover:bg-surface">
            <X className="size-4" aria-hidden />
          </button>
        </div>
      </div>
    )
  }

  return (
    <div>
      <p id="photo-label" className="mb-1.5 text-sm font-medium text-ink">Photo <span className="font-normal text-muted">(optional)</span></p>
      <label
        onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={cx(
          'flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed px-4 py-10 text-center transition-colors duration-150 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-navy',
          dragging ? 'border-navy bg-navy-soft' : 'border-line bg-surface hover:border-gray-300',
        )}
      >
        <ImagePlus className="size-8 text-muted" aria-hidden />
        <span className="text-sm font-semibold text-ink">Drop a photo here, or click to choose</span>
        <span className="text-xs text-muted">JPG or PNG, up to 5 MB</span>
        <input ref={inputRef} type="file" accept="image/*" aria-labelledby="photo-label" className="sr-only"
          onChange={(e) => accept(e.target.files?.[0])} />
      </label>
    </div>
  )
}

/** The backend can only change a listing's image through its URL on edit (PUT is JSON-only). */
function ImageUrlField({ value, category, onChange, error }: { value: string; category: Category; onChange: (e: React.ChangeEvent<HTMLInputElement>) => void; error?: string }) {
  return (
    <div className="space-y-3">
      {value && /^https?:\/\//.test(value) && (
        <img src={value} alt="Current photo" className="aspect-[4/3] w-full rounded-xl bg-surface object-cover" onError={(e) => { e.currentTarget.style.display = 'none' }} />
      )}
      <FormField label="Photo URL" name="imageUrl" type="url" placeholder="https://…" value={value} onChange={onChange} error={error}
        hint={`Leave empty to show the ${formatCategory(category).toLowerCase()} icon instead.`} />
    </div>
  )
}
