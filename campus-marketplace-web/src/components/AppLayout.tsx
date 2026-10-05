import { Outlet } from 'react-router-dom'
import Navbar from './Navbar'
import PhoneBanner from './PhoneBanner'

export default function AppLayout() {
  return (
    <div className="min-h-screen">
      <Navbar />
      <PhoneBanner />
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
