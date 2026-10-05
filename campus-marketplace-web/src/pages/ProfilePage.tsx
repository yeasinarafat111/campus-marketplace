import { useState, type FormEvent } from 'react'
import { changePassword, updateProfile } from '../api/users'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import FormField from '../components/FormField'
import SubmitButton from '../components/SubmitButton'
import { useAuth } from '../hooks/useAuth'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { formatDate, isValidBdPhone, PHONE_ERROR, PHONE_HINT } from '../lib'

export default function ProfilePage() {
  useDocumentTitle('Profile')
  const { user } = useAuth()
  if (!user) return null
  return (
    <div className="max-w-xl">
      <h1 className="text-5xl leading-none">Profile</h1>
      <ProfileForm />
      <PasswordForm />
    </div>
  )
}

function ProfileForm() {
  const { user, setUser } = useAuth()
  const [fullName, setFullName] = useState(user?.fullName ?? '')
  const [phone, setPhone] = useState(user?.phone ?? '')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  if (!user) return null

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setSuccess('')
    setFieldErrors({})
    const errors: Record<string, string> = {}
    if (!fullName.trim()) errors.fullName = 'Full name is required'
    // Accounts from before phones existed may leave it empty; a phone, once set, can't be removed.
    const phoneRequired = user!.phone !== null || phone.trim() !== ''
    if (phoneRequired && !isValidBdPhone(phone)) errors.phone = PHONE_ERROR
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors)
      return
    }
    setLoading(true)
    try {
      const { data } = await updateProfile({
        fullName: fullName.trim(),
        ...(phone.trim() ? { phone: phone.trim() } : {}),
      })
      setUser(data)
      setFullName(data.fullName)
      setPhone(data.phone ?? '')
      setSuccess('Profile saved.')
    } catch (err) {
      const parsed = parseApiError(err)
      setError(parsed.message)
      setFieldErrors(parsed.fieldErrors)
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="mt-6 border-t-2 border-navy pt-6" aria-labelledby="profile-heading">
      <h2 id="profile-heading" className="text-3xl">Your details</h2>
      <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm">
        <dt className="text-muted">Email</dt>
        <dd>{user.email}</dd>
        <dt className="text-muted">Member since</dt>
        <dd>{formatDate(user.createdAt)}</dd>
      </dl>
      <form onSubmit={onSubmit} className="mt-4 space-y-4" noValidate>
        {success && <Alert kind="success">{success}</Alert>}
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Full name" name="fullName" autoComplete="name" required maxLength={100}
          value={fullName} onChange={(e) => setFullName(e.target.value)} error={fieldErrors.fullName} />
        <FormField label="Phone number" name="phone" type="tel" inputMode="tel" autoComplete="tel"
          placeholder={PHONE_HINT} required maxLength={20} value={phone}
          onChange={(e) => setPhone(e.target.value)} error={fieldErrors.phone} />
        <p className="-mt-2 text-sm text-muted">Only the other side of a reservation or order can see it.</p>
        <SubmitButton loading={loading}>Save changes</SubmitButton>
      </form>
    </section>
  )
}

function PasswordForm() {
  const { applyTokens } = useAuth()
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  const update = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }))

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setSuccess('')
    setFieldErrors({})
    if (form.newPassword !== form.confirm) {
      setFieldErrors({ confirm: 'Passwords do not match' })
      return
    }
    setLoading(true)
    try {
      const { data } = await changePassword({
        currentPassword: form.currentPassword,
        newPassword: form.newPassword,
      })
      // The change logs out every other session; these tokens keep this one going.
      applyTokens(data)
      setForm({ currentPassword: '', newPassword: '', confirm: '' })
      setSuccess('Password changed. You have been logged out everywhere else.')
    } catch (err) {
      const parsed = parseApiError(err)
      setError(parsed.message)
      setFieldErrors(parsed.fieldErrors)
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="mt-10 border-t-2 border-navy pt-6" aria-labelledby="password-heading">
      <h2 id="password-heading" className="text-3xl">Change password</h2>
      <form onSubmit={onSubmit} className="mt-4 space-y-4" noValidate>
        {success && <Alert kind="success">{success}</Alert>}
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Current password" name="currentPassword" type="password"
          autoComplete="current-password" required value={form.currentPassword}
          onChange={update('currentPassword')} error={fieldErrors.currentPassword} />
        <FormField label="New password" name="newPassword" type="password" autoComplete="new-password"
          required minLength={8} maxLength={72} value={form.newPassword} onChange={update('newPassword')}
          error={fieldErrors.newPassword} />
        <FormField label="Confirm new password" name="confirm" type="password" autoComplete="new-password"
          required minLength={8} maxLength={72} value={form.confirm} onChange={update('confirm')}
          error={fieldErrors.confirm} />
        <p className="text-sm text-muted">8 to 72 characters.</p>
        <SubmitButton loading={loading}>Change password</SubmitButton>
      </form>
    </section>
  )
}
