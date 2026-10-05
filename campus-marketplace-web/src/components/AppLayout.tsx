import { Outlet } from 'react-router-dom'
import Footer from './Footer'
import Header from './Header'
import PhoneBanner from './PhoneBanner'

export default function AppLayout() {
  return (
    <div className="flex min-h-screen flex-col">
      <Header />
      <PhoneBanner />
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-6 sm:py-8">
        <Outlet />
      </main>
      <Footer />
    </div>
  )
}
