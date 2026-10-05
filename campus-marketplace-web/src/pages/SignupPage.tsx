import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { signup } from '../api/auth'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import SubmitButton from '../components/SubmitButton'
import { isValidBdPhone, PHONE_ERROR, PHONE_HINT } from '../lib'

export default function SignupPage() {
  useDocumentTitle('Sign up')
  const navigate = useNavigate()
  const [form, setForm] = useState({ fullName: '', email: '', phone: '', password: '' })
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  const update = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }))

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setFieldErrors({})
    if (!isValidBdPhone(form.phone)) {
      setFieldErrors({ phone: PHONE_ERROR })
      return
    }
    setLoading(true)
    try {
      await signup({ ...form, fullName: form.fullName.trim(), email: form.email.trim(), phone: form.phone.trim() })
      navigate(`/verify-otp?email=${encodeURIComponent(form.email.trim())}`)
    } catch (err) {
      const parsed = parseApiError(err)
      setError(parsed.message)
      setFieldErrors(parsed.fieldErrors)
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      subtitle="Use your seu.edu.bd email address."
      footer={
        <>
          Already have an account?{' '}
          <Link to="/login" className="link">
            Log in
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Full name" name="fullName" autoComplete="name" required maxLength={100}
          value={form.fullName} onChange={update('fullName')} error={fieldErrors.fullName} />
        <FormField label="Email" name="email" type="email" autoComplete="email" required
          value={form.email} onChange={update('email')} error={fieldErrors.email} />
        <FormField label="Phone number" name="phone" type="tel" inputMode="tel" autoComplete="tel"
          placeholder={PHONE_HINT} required maxLength={20} value={form.phone} onChange={update('phone')}
          error={fieldErrors.phone} />
        <p className="-mt-2 text-sm text-muted">Buyers and sellers use it to arrange meetups. It is never shown publicly.</p>
        <FormField label="Password" name="password" type="password" autoComplete="new-password" required
          minLength={8} maxLength={72} value={form.password} onChange={update('password')}
          error={fieldErrors.password} />
        <p className="text-sm text-muted">8 to 72 characters.</p>
        <SubmitButton loading={loading}>Sign up</SubmitButton>
      </form>
    </AuthLayout>
  )
}
