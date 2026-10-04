import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'

/** Renders nested routes only for logged-in users; everyone else is sent to /login. */
export default function ProtectedRoute() {
  const { isAuthenticated, isLoading } = useAuth()
  const location = useLocation()
  if (isLoading) return null
  return isAuthenticated ? <Outlet /> : <Navigate to="/login" replace state={{ from: location }} />
}
