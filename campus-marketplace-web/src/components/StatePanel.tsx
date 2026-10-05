import type { ReactNode } from 'react'

/** Full-width message block for empty, not-found and failure states. */
export default function StatePanel({ title, children, actions }: { title: string; children?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mx-auto max-w-xl border-t-4 border-navy pt-6">
      <h1 className="text-4xl">{title}</h1>
      {children && <div className="mt-3 text-lg text-muted">{children}</div>}
      {actions && <div className="mt-6 flex flex-wrap gap-3">{actions}</div>}
    </div>
  )
}
