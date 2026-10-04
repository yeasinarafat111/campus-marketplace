import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'

/** Like ProtectedRoute, but additionally requires role ADMIN. Non-admins go back to /listings. */
export default function AdminRoute() {
  const { user, isLoading } = useAuth()
  const location = useLocation()
  if (isLoading) return null
  if (!user) return <Navigate to="/login" replace state={{ from: location }} />
  return user.role === 'ADMIN' ? <Outlet /> : <Navigate to="/listings" replace />
}
