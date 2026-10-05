import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { resetPassword } from '../api/auth'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import SubmitButton from '../components/SubmitButton'
import { LINK_CLASS } from '../lib'

export default function ResetPasswordPage() {
  useDocumentTitle('Reset password')
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const notice = (useLocation().state as { notice?: string } | null)?.notice
  const [email, setEmail] = useState(params.get('email') ?? '')
  const [code, setCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setFieldErrors({})
    if (newPassword !== confirm) {
      setFieldErrors({ confirm: 'Passwords do not match' })
      return
    }
    setLoading(true)
    try {
      await resetPassword({ email: email.trim(), code: code.trim(), newPassword })
      navigate('/login', { state: { reset: true } })
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
      title="Reset password"
      subtitle="Enter the code from your email and choose a new password."
      footer={
        <Link to="/login" className={LINK_CLASS}>
          Back to log in
        </Link>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {notice && !error && <Alert kind="success">{notice}</Alert>}
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Email" name="email" type="email" autoComplete="email" required
          value={email} onChange={(e) => setEmail(e.target.value)} error={fieldErrors.email} />
        <FormField label="Reset code" name="code" inputMode="numeric" autoComplete="one-time-code"
          pattern="\d{6}" maxLength={6} required value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} error={fieldErrors.code} />
        <FormField label="New password" name="newPassword" type="password" autoComplete="new-password"
          required minLength={8} maxLength={72} value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)} error={fieldErrors.newPassword} />
        <FormField label="Confirm new password" name="confirm" type="password" autoComplete="new-password"
          required minLength={8} maxLength={72} value={confirm}
          onChange={(e) => setConfirm(e.target.value)} error={fieldErrors.confirm} />
        <p className="text-sm text-muted">8 to 72 characters.</p>
        <SubmitButton loading={loading}>Reset password</SubmitButton>
        <p className="text-center text-sm text-muted">
          Didn't get a code?{' '}
          <Link to={`/forgot-password${email.trim() ? `?email=${encodeURIComponent(email.trim())}` : ''}`} className={LINK_CLASS}>
            Request another
          </Link>
        </p>
      </form>
    </AuthLayout>
  )
}
