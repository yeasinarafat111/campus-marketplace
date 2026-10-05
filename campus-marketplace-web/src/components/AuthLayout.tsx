import type { ReactNode } from 'react'
import Logo from './Logo'

/** Centered card on a quiet background, shared by login, signup, verify and password reset. */
export default function AuthLayout({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-surface px-4 py-10">
      <Logo className="mb-6" />
      <main className="w-full max-w-md rounded-2xl border border-line bg-white p-6 shadow-sm sm:p-8">
        <h1 className="text-2xl font-bold tracking-tight text-navy">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
        <div className="mt-6">{children}</div>
        {footer && <div className="mt-6 border-t border-line pt-4 text-center text-sm text-muted">{footer}</div>}
      </main>
      <p className="mt-6 text-sm text-muted">Only @seu.edu.bd students</p>
    </div>
  )
}
