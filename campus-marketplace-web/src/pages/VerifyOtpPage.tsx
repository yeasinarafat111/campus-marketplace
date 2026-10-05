import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { resendOtp, verifyOtp } from '../api/auth'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import Button from '../components/ui/Button'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import SubmitButton from '../components/SubmitButton'
import { LINK_CLASS } from '../lib'

export default function VerifyOtpPage() {
  useDocumentTitle('Verify your email')
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [email, setEmail] = useState(params.get('email') ?? '')
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setInfo('')
    setFieldErrors({})
    setLoading(true)
    try {
      await verifyOtp({ email: email.trim(), code: code.trim() })
      navigate('/login', { state: { verified: true } })
    } catch (err) {
      const parsed = parseApiError(err)
      setError(parsed.message)
      setFieldErrors(parsed.fieldErrors)
    } finally {
      setLoading(false)
    }
  }

  async function onResend() {
    setError('')
    setInfo('')
    try {
      const { data } = await resendOtp({ email: email.trim() })
      setInfo(data.message)
    } catch (err) {
      setError(parseApiError(err).message)
    }
  }

  return (
    <AuthLayout
      title="Verify your email"
      subtitle="Enter the 6-digit code we emailed you."
      footer={
        <Link to="/login" className={LINK_CLASS}>
          Back to log in
        </Link>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {error && <Alert kind="error">{error}</Alert>}
        {info && <Alert kind="success">{info}</Alert>}
        <FormField label="Email" name="email" type="email" autoComplete="email" required
          value={email} onChange={(e) => setEmail(e.target.value)} error={fieldErrors.email} />
        <FormField label="Verification code" name="code" inputMode="numeric" autoComplete="one-time-code"
          pattern="\d{6}" maxLength={6} required value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} error={fieldErrors.code} />
        <SubmitButton loading={loading}>Verify</SubmitButton>
        <Button variant="ghost" onClick={onResend} className="w-full">
          Resend code
        </Button>
      </form>
    </AuthLayout>
  )
}
