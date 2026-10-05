import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

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
    <div className="grid min-h-screen md:grid-cols-[2fr_3fr]">
      <aside className="on-navy flex flex-col justify-between border-b-4 border-gold bg-navy px-6 py-6 text-white md:border-b-0 md:border-r-4 md:px-10 md:py-12">
        <Link to="/listings" className="font-display text-3xl font-bold tracking-wide text-white">
          Campus Marketplace
        </Link>
        <p className="mt-10 hidden max-w-xs font-display text-4xl font-semibold leading-tight text-white md:block">
          Textbooks, gadgets and dorm gear, from students on your campus.
        </p>
        <p className="hidden text-sm text-white/70 md:block">Sign-up is open to seu.edu.bd email addresses.</p>
      </aside>
      <main className="flex items-center justify-center px-4 py-10">
        <div className="w-full max-w-md">
          <h1 className="text-4xl">{title}</h1>
          {subtitle && <p className="mt-1 text-muted">{subtitle}</p>}
          <div className="mt-6 border-t-2 border-navy pt-6">{children}</div>
          {footer && <div className="mt-6 border-t border-rule pt-4 text-sm text-muted">{footer}</div>}
        </div>
      </main>
    </div>
  )
}
