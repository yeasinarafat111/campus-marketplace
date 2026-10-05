import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { changePassword, updateProfile } from '../api/users'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import FormField from '../components/FormField'
import Avatar from '../components/ui/Avatar'
import Button from '../components/ui/Button'
import { useAuth } from '../hooks/useAuth'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { formatDate, isValidBdPhone, PHONE_ERROR, PHONE_HINT } from '../lib'

export default function ProfilePage() {
  useDocumentTitle('Profile')
  const { user } = useAuth()
  if (!user) return null
  return (
    <div className="mx-auto max-w-xl space-y-8">
      <div className="flex items-center gap-4">
        <Avatar name={user.fullName} size="lg" />
        <div className="min-w-0">
          <h1 className="truncate text-2xl font-bold tracking-tight text-navy">{user.fullName}</h1>
          <p className="text-sm text-muted">Member since {formatDate(user.createdAt)}</p>
        </div>
      </div>
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
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  if (!user) return null

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
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
      toast.success('Profile saved')
    } catch (err) {
      const parsed = parseApiError(err)
      setError(parsed.message)
      setFieldErrors(parsed.fieldErrors)
    } finally {
      setLoading(false)
    }
  }

  return (
    <section aria-labelledby="profile-heading" className="rounded-xl border border-line p-5 sm:p-6">
      <h2 id="profile-heading" className="text-lg font-bold text-navy">Your details</h2>
      <form onSubmit={onSubmit} className="mt-4 space-y-4" noValidate>
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Email" name="email" value={user.email} readOnly disabled hint="Your email can't be changed." />
        <FormField label="Full name" name="fullName" autoComplete="name" required maxLength={100}
          value={fullName} onChange={(e) => setFullName(e.target.value)} error={fieldErrors.fullName} />
        <FormField label="Phone number" name="phone" type="tel" inputMode="tel" autoComplete="tel"
          placeholder={PHONE_HINT} required maxLength={20} value={phone}
          onChange={(e) => setPhone(e.target.value)} error={fieldErrors.phone}
          hint="Only the other side of a reservation or order can see it." />
        <Button type="submit" loading={loading}>Save changes</Button>
      </form>
    </section>
  )
}

function PasswordForm() {
  const { applyTokens } = useAuth()
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  const update = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }))

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
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
      toast.success('Password changed', { description: 'You have been logged out everywhere else.' })
    } catch (err) {
      const parsed = parseApiError(err)
      setError(parsed.message)
      setFieldErrors(parsed.fieldErrors)
    } finally {
      setLoading(false)
    }
  }

  return (
    <section aria-labelledby="password-heading" className="rounded-xl border border-line p-5 sm:p-6">
      <h2 id="password-heading" className="text-lg font-bold text-navy">Change password</h2>
      <form onSubmit={onSubmit} className="mt-4 space-y-4" noValidate>
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Current password" name="currentPassword" type="password"
          autoComplete="current-password" required value={form.currentPassword}
          onChange={update('currentPassword')} error={fieldErrors.currentPassword} />
        <FormField label="New password" name="newPassword" type="password" autoComplete="new-password"
          required minLength={8} maxLength={72} value={form.newPassword} onChange={update('newPassword')}
          error={fieldErrors.newPassword} hint="8 to 72 characters." />
        <FormField label="Confirm new password" name="confirm" type="password" autoComplete="new-password"
          required minLength={8} maxLength={72} value={form.confirm} onChange={update('confirm')}
          error={fieldErrors.confirm} />
        <Button type="submit" loading={loading}>Change password</Button>
      </form>
    </section>
  )
}
