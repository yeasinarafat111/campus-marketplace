import type { ComponentProps } from 'react'
import { cx } from '../../lib'

export const controlClasses =
  'block w-full rounded-lg border border-line bg-white px-3 text-sm text-ink placeholder:text-muted transition-colors duration-150 hover:border-gray-300 aria-[invalid=true]:border-danger disabled:bg-surface'

export function Input({ className, ...props }: ComponentProps<'input'>) {
  return <input className={cx(controlClasses, 'h-10', className)} {...props} />
}

export function Select({ className, children, ...props }: ComponentProps<'select'>) {
  return (
    <select className={cx(controlClasses, 'h-10', className)} {...props}>
      {children}
    </select>
  )
}

export function Textarea({ className, ...props }: ComponentProps<'textarea'>) {
  return <textarea className={cx(controlClasses, 'py-2', className)} {...props} />
}
