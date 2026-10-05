import type { ReactNode } from 'react'
import { cx } from '../../lib'

export type BadgeTone = 'neutral' | 'success' | 'warning' | 'muted' | 'danger' | 'featured'

const TONES: Record<BadgeTone, string> = {
  neutral: 'bg-surface text-ink',
  success: 'bg-success-soft text-success',
  warning: 'bg-warning-soft text-warning',
  muted: 'bg-gray-200 text-gray-700',
  danger: 'bg-danger-soft text-danger',
  featured: 'bg-gold text-navy',
}

export default function Badge({ tone = 'neutral', children }: { tone?: BadgeTone; children: ReactNode }) {
  return (
    <span className={cx('inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-semibold', TONES[tone])}>
      {children}
    </span>
  )
}
