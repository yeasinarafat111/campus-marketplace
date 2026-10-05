import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { CATEGORIES, CONDITION_LABELS, ITEM_CONDITIONS, MEETUP_LABELS, MEETUP_SPOTS, addListingPhoto, createListing, getListing, getPostingFee, removeListingPhoto, reorderListingPhotos, updateListing, type Category, type Listing, type ListingInput, type ListingPhoto } from '../api/listings'
import { parseApiError } from '../api/errors'
import { createCheckoutSession } from '../api/payments'
import Alert from '../components/Alert'
import FormField from '../components/FormField'
import { PhotoManager } from '../components/PhotoManager'
import Button from '../components/ui/Button'
import Field from '../components/ui/Field'
import { Input, Select, Textarea } from '../components/ui/Input'
import Skeleton from '../components/ui/Skeleton'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { cx, formatCategory, formatCents, formatPrice, rememberCheckout } from '../lib'
import NotFoundPage from './NotFoundPage'
import { isAxiosError } from 'axios'

const MAX_DESCRIPTION = 2000

const empty: ListingInput = { title: '', description: '', price: '', category: 'OTHER', imageUrl: '', meetupSpot: '', itemCondition: '' }

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
          meetupSpot: existing.meetupSpot ?? '',
          itemCondition: existing.itemCondition ?? '',
        }
      : empty,
  )
  // New listing: files chosen so far (uploaded with the form). Edit: the saved photos, changed immediately.
  const [files, setFiles] = useState<File[]>([])
  const previews = useMemo(() => files.map((f) => ({ key: `${f.name}-${f.size}-${f.lastModified}`, url: URL.createObjectURL(f) })), [files])
  useEffect(() => () => previews.forEach((p) => URL.revokeObjectURL(p.url)), [previews])
  const [photos, setPhotos] = useState<ListingPhoto[]>(existing?.images ?? [])
  const [photoBusy, setPhotoBusy] = useState(false)
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const save = useMutation({
    mutationFn: () => (existing ? updateListing(existing.id, form) : createListing(form, files)),
    onSuccess: async (saved) => {
      queryClient.invalidateQueries({ queryKey: ['listings'] })
      queryClient.invalidateQueries({ queryKey: ['my-listings'] })
      queryClient.invalidateQueries({ queryKey: ['listing', String(saved.id)] })
      if (!isEdit && saved.status === 'PENDING_PAYMENT') {
        // Priced above the free tier: it isn't public until the posting fee is paid, so go straight to Stripe.
        try {
          const session = await createCheckoutSession({ listingId: saved.id, purpose: 'POSTING_FEE' })
          if (!session.url.startsWith('https://')) throw new Error('Unexpected checkout URL')
          rememberCheckout(saved.id, 'POSTING_FEE')
          window.location.href = session.url
          return
        } catch (e) {
          toast.error(parseApiError(e).message, { description: 'Your listing is saved but not published. Pay from My listings.' })
          navigate('/my-listings?tab=pending')
          return
        }
      }
      toast.success(isEdit ? 'Listing updated' : 'Listing created')
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

  /** Runs a saved-photo change straight away; the server returns the listing with its photos in their new state. */
  async function editPhotos(run: () => Promise<Listing | undefined>) {
    setPhotoBusy(true)
    setError('')
    try {
      const updated = await run()
      if (updated) {
        setPhotos(updated.images)
        queryClient.invalidateQueries({ queryKey: ['listings'] })
        queryClient.invalidateQueries({ queryKey: ['my-listings'] })
        queryClient.invalidateQueries({ queryKey: ['listing', String(updated.id)] })
      }
    } catch (e) {
      setError(parseApiError(e).message)
    } finally {
      setPhotoBusy(false)
    }
  }

  const price = Number(form.price)
  const priceValid = form.price.trim() !== '' && Number.isFinite(price) && price >= 0
  // The fee comes from the server, so the page never works out an amount itself.
  const postingFee = useQuery({
    queryKey: ['posting-fee', price],
    queryFn: () => getPostingFee(price),
    enabled: !isEdit && priceValid,
    staleTime: 60_000,
  })

  return (
    <div className="mx-auto max-w-xl">
      <h1 className="mb-6 text-2xl font-bold tracking-tight text-navy">{isEdit ? 'Edit listing' : 'Sell something'}</h1>
      <form onSubmit={onSubmit} className="space-y-5" noValidate>
        {error && <Alert kind="error">{error}</Alert>}

        {existing ? (
          <PhotoManager
            items={photos.map((p) => ({ key: p.id, url: p.url }))}
            busy={photoBusy}
            onError={setError}
            onAdd={(added) => editPhotos(async () => {
              let latest: Listing | undefined
              for (const f of added) latest = await addListingPhoto(existing.id, f)
              return latest
            })}
            onRemove={(i) => editPhotos(() => removeListingPhoto(existing.id, photos[i].id))}
            onMove={(from, to) => {
              const ids = photos.map((p) => p.id)
              ids.splice(to, 0, ids.splice(from, 1)[0])
              return editPhotos(() => reorderListingPhotos(existing.id, ids))
            }}
          />
        ) : (
          <PhotoManager
            items={previews}
            onError={setError}
            onAdd={(added) => setFiles((f) => [...f, ...added])}
            onRemove={(i) => setFiles((f) => f.filter((_, j) => j !== i))}
            onMove={(from, to) => setFiles((f) => { const next = [...f]; next.splice(to, 0, next.splice(from, 1)[0]); return next })}
          />
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

        {!isEdit && postingFee.data && (
          <p role="status" className={cx('rounded-lg px-3 py-2 text-sm', postingFee.data.applies ? 'bg-navy-soft text-navy' : 'bg-success-soft text-success')}>
            {postingFee.data.applies
              ? <>Posting fee: <strong className="tabular-nums">{formatCents(postingFee.data.feeCents)}</strong> ({Number((postingFee.data.rate * 100).toFixed(2))}%), paid after you submit. Your listing goes live once it's paid.</>
              : <>Free to post (up to {formatCents(postingFee.data.thresholdCents)}).</>}
          </p>
        )}

        <Field label="Condition (optional)" error={fieldErrors.itemCondition}>
          <Select value={form.itemCondition} onChange={set('itemCondition')}>
            <option value="">Not stated</option>
            {ITEM_CONDITIONS.map((c) => (
              <option key={c} value={c}>{CONDITION_LABELS[c]}</option>
            ))}
          </Select>
        </Field>

        <Field label="Meetup spot (optional)" hint="Where on campus you prefer to hand the item over." error={fieldErrors.meetupSpot}>
          <Select value={form.meetupSpot} onChange={set('meetupSpot')}>
            <option value="">No preference</option>
            {MEETUP_SPOTS.map((spot) => (
              <option key={spot} value={spot}>{MEETUP_LABELS[spot]}</option>
            ))}
          </Select>
        </Field>

        <Button type="submit" size="lg" loading={save.isPending} className="w-full">
          {isEdit ? 'Save changes' : postingFee.data?.applies ? 'Continue to payment' : 'Post listing'}
        </Button>
      </form>
    </div>
  )
}
