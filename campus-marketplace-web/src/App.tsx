import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import AdminRoute from './components/AdminRoute'
import AppLayout from './components/AppLayout'
import ProtectedRoute from './components/ProtectedRoute'
import { AuthProvider } from './context/AuthProvider'
import AdminDashboardPage from './pages/AdminDashboardPage'
import CreateEditListingPage from './pages/CreateEditListingPage'
import ForgotPasswordPage from './pages/ForgotPasswordPage'
import ListingDetailPage from './pages/ListingDetailPage'
import ListingsPage from './pages/ListingsPage'
import LoginPage from './pages/LoginPage'
import MyListingsPage from './pages/MyListingsPage'
import PaymentCancelPage from './pages/PaymentCancelPage'
import PaymentSuccessPage from './pages/PaymentSuccessPage'
import ResetPasswordPage from './pages/ResetPasswordPage'
import SignupPage from './pages/SignupPage'
import VerifyOtpPage from './pages/VerifyOtpPage'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/verify-otp" element={<VerifyOtpPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />

          <Route element={<AppLayout />}>
            {/* Browsing is public on the backend. */}
            <Route path="/listings" element={<ListingsPage />} />
            <Route path="/listings/:id" element={<ListingDetailPage />} />

            <Route element={<ProtectedRoute />}>
              <Route path="/listings/new" element={<CreateEditListingPage />} />
              <Route path="/listings/:id/edit" element={<CreateEditListingPage />} />
              <Route path="/my-listings" element={<MyListingsPage />} />
              <Route path="/payments/success" element={<PaymentSuccessPage />} />
              <Route path="/payments/cancel" element={<PaymentCancelPage />} />
            </Route>

            <Route element={<AdminRoute />}>
              <Route path="/admin" element={<AdminDashboardPage />} />
            </Route>
          </Route>

          <Route path="*" element={<Navigate to="/listings" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
