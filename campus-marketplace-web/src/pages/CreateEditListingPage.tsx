import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { CATEGORIES, createListing, getListing, updateListing, type Category, type ListingInput } from '../api/listings'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import FormField from '../components/FormField'
import SubmitButton from '../components/SubmitButton'
import { formatCategory } from '../lib'

const MAX_IMAGE_BYTES = 5 * 1024 * 1024 // matches the backend's multipart limit

const empty: ListingInput = { title: '', description: '', price: '', category: 'OTHER', imageUrl: '' }

export default function CreateEditListingPage() {
  const { id } = useParams()
  const isEdit = id !== undefined
  useDocumentTitle(isEdit ? 'Edit listing' : 'Sell something')
  const navigate = useNavigate()
  const [form, setForm] = useState<ListingInput>(empty)
  const [image, setImage] = useState<File | null>(null)
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!isEdit) return
    let cancelled = false
    getListing(id)
      .then((l) => {
        if (cancelled) return
        setForm({
          title: l.title,
          description: l.description ?? '',
          price: String(l.price),
          category: l.category,
          imageUrl: l.imageUrl ?? '',
        })
      })
      .catch((e) => !cancelled && setError(parseApiError(e).message))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [id, isEdit])

  const preview = useMemo(() => (image ? URL.createObjectURL(image) : null), [image])
  useEffect(() => {
    return () => {
      if (preview) URL.revokeObjectURL(preview)
    }
  }, [preview])

  const set = (key: keyof ListingInput) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }))

  function onImage(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0] ?? null
    if (file && file.size > MAX_IMAGE_BYTES) {
      setError('Image must be 5 MB or smaller.')
      e.target.value = ''
      return
    }
    setError('')
    setImage(file)
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setFieldErrors({})
    setSaving(true)
    try {
      const saved = isEdit ? await updateListing(Number(id), form) : await createListing(form, image)
      navigate(`/listings/${saved.id}`)
    } catch (err) {
      const parsed = parseApiError(err)
      setError(parsed.message)
      setFieldErrors(parsed.fieldErrors)
      setSaving(false)
    }
  }

  if (loading) return <p className="text-muted">Loading…</p>

  return (
    <div className="mx-auto max-w-xl">
      <h1 className="mb-6 border-b-2 border-navy pb-2 text-5xl leading-none">{isEdit ? 'Edit listing' : 'Sell something'}</h1>
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Title" name="title" required maxLength={120} value={form.title}
          onChange={set('title')} error={fieldErrors.title} />
        <div>
          <label htmlFor="description" className="block text-sm font-semibold text-navy">Description</label>
          <textarea id="description" rows={4} maxLength={2000} value={form.description} onChange={set('description')}
            className="input mt-1" />
          {fieldErrors.description && <p className="mt-1 text-sm text-danger">{fieldErrors.description}</p>}
        </div>
        <div className="grid grid-cols-2 gap-4">
          <FormField label="Price (USD)" name="price" type="number" min="0" step="0.01" required
            value={form.price} onChange={set('price')} error={fieldErrors.price} />
          <div>
            <label htmlFor="category" className="block text-sm font-semibold text-navy">Category</label>
            <select id="category" value={form.category} onChange={set('category')}
              className="input mt-1">
              {CATEGORIES.map((c: Category) => (
                <option key={c} value={c}>{formatCategory(c)}</option>
              ))}
            </select>
          </div>
        </div>
        {isEdit ? (
          <FormField label="Image URL" name="imageUrl" type="url" placeholder="https://…" value={form.imageUrl ?? ''}
            onChange={set('imageUrl')} error={fieldErrors.imageUrl} />
        ) : (
          <div>
            <label htmlFor="image" className="block text-sm font-semibold text-navy">Image (optional, max 5 MB)</label>
            <input id="image" type="file" accept="image/*" onChange={onImage}
              className="mt-1 block w-full text-sm file:mr-3 file:rounded-sm file:border file:border-navy file:bg-transparent file:px-3 file:py-2 file:font-semibold file:text-navy" />
            {preview && <img src={preview} alt="Preview" className="mt-3 h-40 border border-rule object-cover" />}
          </div>
        )}
        <SubmitButton loading={saving}>{isEdit ? 'Save changes' : 'Create listing'}</SubmitButton>
      </form>
    </div>
  )
}
