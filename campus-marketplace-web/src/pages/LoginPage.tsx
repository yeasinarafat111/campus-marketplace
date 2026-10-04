import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import SubmitButton from '../components/SubmitButton'
import { useAuth } from '../hooks/useAuth'

export default function LoginPage() {
  const { login, isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const verified = (location.state as { verified?: boolean } | null)?.verified
  const resetDone = (location.state as { reset?: boolean } | null)?.reset
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  if (isAuthenticated) return <Navigate to="/listings" replace />

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await login(email.trim(), password)
      navigate('/listings', { replace: true })
    } catch (err) {
      setError(parseApiError(err).message)
      setLoading(false)
    }
  }

  return (
    <AuthLayout
      title="Log in"
      footer={
        <>
          New here?{' '}
          <Link to="/signup" className="font-medium text-indigo-600 hover:underline">
            Create an account
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {verified && <Alert kind="success">Email verified. You can log in now.</Alert>}
        {resetDone && <Alert kind="success">Password updated. Log in with your new password.</Alert>}
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Email" name="email" type="email" autoComplete="email" required
          value={email} onChange={(e) => setEmail(e.target.value)} />
        <FormField label="Password" name="password" type="password" autoComplete="current-password" required
          value={password} onChange={(e) => setPassword(e.target.value)} />
        <div className="text-right text-sm">
          <Link to="/forgot-password" className="text-indigo-600 hover:underline">
            Forgot password?
          </Link>
        </div>
        <SubmitButton loading={loading}>Log in</SubmitButton>
      </form>
    </AuthLayout>
  )
}
