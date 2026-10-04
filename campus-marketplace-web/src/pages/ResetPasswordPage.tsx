import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { resetPassword } from '../api/auth'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import SubmitButton from '../components/SubmitButton'

export default function ResetPasswordPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [email, setEmail] = useState(params.get('email') ?? '')
  const [code, setCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setFieldErrors({})
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
        <Link to="/login" className="font-medium text-indigo-600 hover:underline">
          Back to log in
        </Link>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Email" name="email" type="email" autoComplete="email" required
          value={email} onChange={(e) => setEmail(e.target.value)} error={fieldErrors.email} />
        <FormField label="Reset code" name="code" inputMode="numeric" autoComplete="one-time-code"
          pattern="\d{6}" maxLength={6} required value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} error={fieldErrors.code} />
        <FormField label="New password" name="newPassword" type="password" autoComplete="new-password"
          required minLength={8} maxLength={72} value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)} error={fieldErrors.newPassword} />
        <SubmitButton loading={loading}>Reset password</SubmitButton>
      </form>
    </AuthLayout>
  )
}
