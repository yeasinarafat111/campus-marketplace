import type { ReactNode } from 'react'

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
    <main className="flex min-h-screen items-center justify-center bg-gray-50 px-4 py-12">
      <div className="w-full max-w-md">
        <p className="mb-6 text-center text-sm font-semibold tracking-wide text-indigo-600">Campus Marketplace</p>
        <div className="rounded-xl bg-white p-8 shadow-sm ring-1 ring-gray-200">
          <h1 className="text-2xl font-bold text-gray-900">{title}</h1>
          {subtitle && <p className="mt-1 text-sm text-gray-600">{subtitle}</p>}
          <div className="mt-6">{children}</div>
        </div>
        {footer && <div className="mt-4 text-center text-sm text-gray-600">{footer}</div>}
      </div>
    </main>
  )
}
