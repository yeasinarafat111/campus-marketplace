import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import EmptyState from './ui/EmptyState'

/** Full-width message block for empty, not-found and failure states. */
export default function StatePanel({
  icon,
  title,
  children,
  actions,
  note,
}: {
  icon: LucideIcon
  title: string
  children?: ReactNode
  actions?: ReactNode
  /** Small print under the card, e.g. the demo-mode notice. */
  note?: ReactNode
}) {
  return (
    <div className="mx-auto max-w-xl py-8">
      <EmptyState icon={icon} title={title} action={actions}>
        {children}
      </EmptyState>
      {note && <div className="mt-4">{note}</div>}
    </div>
  )
}
