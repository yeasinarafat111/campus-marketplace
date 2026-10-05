import { AlertCircle, CheckCircle2 } from 'lucide-react'
import type { ReactNode } from 'react'

/** Inline message for a form or section. For the result of an action, prefer a toast. */
export default function Alert({ kind, children }: { kind: 'error' | 'success'; children: ReactNode }) {
  const isError = kind === 'error'
  const Icon = isError ? AlertCircle : CheckCircle2
  return (
    <div
      role={isError ? 'alert' : 'status'}
      className={`flex items-start gap-2 rounded-lg px-3 py-2.5 text-sm ${isError ? 'bg-danger-soft text-danger' : 'bg-success-soft text-success'}`}
    >
      <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div>{children}</div>
    </div>
  )
}
