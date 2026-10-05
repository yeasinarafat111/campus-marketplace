import { lazy, Suspense } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import AdminRoute from './components/AdminRoute'
import AppLayout from './components/AppLayout'
import ErrorBoundary from './components/ErrorBoundary'
import ProtectedRoute from './components/ProtectedRoute'
import { AuthProvider } from './context/AuthProvider'
import { ChatSocketProvider } from './context/ChatSocketProvider'

const MessagesPage = lazy(() => import('./pages/MessagesPage'))
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'))
const AdminDashboardPage = lazy(() => import('./pages/AdminDashboardPage'))
const AdminRevenuePage = lazy(() => import('./pages/AdminRevenuePage'))
const CreateEditListingPage = lazy(() => import('./pages/CreateEditListingPage'))
const ForgotPasswordPage = lazy(() => import('./pages/ForgotPasswordPage'))
const ListingDetailPage = lazy(() => import('./pages/ListingDetailPage'))
const ListingsPage = lazy(() => import('./pages/ListingsPage'))
const LoginPage = lazy(() => import('./pages/LoginPage'))
const MyListingsPage = lazy(() => import('./pages/MyListingsPage'))
const SellerProfilePage = lazy(() => import('./pages/SellerProfilePage'))
const SavedPage = lazy(() => import('./pages/SavedPage'))
const OrdersPage = lazy(() => import('./pages/OrdersPage'))
const PaymentCancelPage = lazy(() => import('./pages/PaymentCancelPage'))
const ProfilePage = lazy(() => import('./pages/ProfilePage'))
const PaymentSuccessPage = lazy(() => import('./pages/PaymentSuccessPage'))
const ResetPasswordPage = lazy(() => import('./pages/ResetPasswordPage'))
const SignupPage = lazy(() => import('./pages/SignupPage'))
const VerifyOtpPage = lazy(() => import('./pages/VerifyOtpPage'))

/** Shown while a page's code is fetched (the first visit to each page). */
function PageFallback() {
  return (
    <div className="mx-auto max-w-7xl px-4 py-8" aria-busy="true" aria-label="Loading">
      <div className="skeleton h-8 w-48" />
      <div className="skeleton mt-6 h-64 w-full" />
    </div>
  )
}

export default function App() {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <AuthProvider>
          <ChatSocketProvider>
          <Suspense fallback={<PageFallback />}>
          <Routes>
            <Route path="/" element={<Navigate to="/listings" replace />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/verify-otp" element={<VerifyOtpPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />

            <Route element={<AppLayout />}>
              {/* Browsing is public on the backend. */}
              <Route path="/listings" element={<ListingsPage />} />
              <Route path="/listings/:id" element={<ListingDetailPage />} />
              <Route path="/users/:id" element={<SellerProfilePage />} />

              <Route element={<ProtectedRoute />}>
                <Route path="/listings/new" element={<CreateEditListingPage />} />
                <Route path="/listings/:id/edit" element={<CreateEditListingPage />} />
                <Route path="/my-listings" element={<MyListingsPage />} />
                <Route path="/saved" element={<SavedPage />} />
                <Route path="/profile" element={<ProfilePage />} />
                <Route path="/messages" element={<MessagesPage />} />
                <Route path="/messages/:id" element={<MessagesPage />} />
                <Route path="/purchases" element={<OrdersPage kind="purchases" />} />
                <Route path="/sales" element={<OrdersPage kind="sales" />} />
                <Route path="/payments/success" element={<PaymentSuccessPage />} />
                <Route path="/payments/cancel" element={<PaymentCancelPage />} />
              </Route>

              <Route element={<AdminRoute />}>
                <Route path="/admin" element={<AdminDashboardPage />} />
                <Route path="/admin/revenue" element={<AdminRevenuePage />} />
              </Route>

              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Routes>
          </Suspense>
          </ChatSocketProvider>
        </AuthProvider>
      </BrowserRouter>
    </ErrorBoundary>
  )
}
