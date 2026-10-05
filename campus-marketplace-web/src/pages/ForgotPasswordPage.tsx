import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { forgotPassword } from '../api/auth'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import SubmitButton from '../components/SubmitButton'
import { LINK_CLASS } from '../lib'

export default function ForgotPasswordPage() {
  useDocumentTitle('Forgot password')
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [email, setEmail] = useState(params.get('email') ?? '')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const { data } = await forgotPassword({ email: email.trim() })
      navigate(`/reset-password?email=${encodeURIComponent(email.trim())}`, { state: { notice: data.message } })
    } catch (err) {
      setError(parseApiError(err).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout
      title="Forgot password"
      subtitle="We'll email you a code to reset your password."
      footer={
        <Link to="/login" className={LINK_CLASS}>
          Back to log in
        </Link>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Email" name="email" type="email" autoComplete="email" required
          value={email} onChange={(e) => setEmail(e.target.value)} />
        <SubmitButton loading={loading}>Send reset code</SubmitButton>
      </form>
    </AuthLayout>
  )
}
