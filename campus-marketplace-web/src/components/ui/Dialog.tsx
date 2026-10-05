import * as RadixDialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'
import { cx } from '../../lib'
import Button from './Button'

type Side = 'center' | 'left' | 'right' | 'bottom'

const PANEL: Record<Side, string> = {
  center: 'left-1/2 top-1/2 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl p-6',
  left: 'inset-y-0 left-0 w-72 max-w-[85%] p-5',
  right: 'inset-y-0 right-0 w-80 max-w-[90%] p-5',
  bottom: 'inset-x-0 bottom-0 max-h-[85vh] overflow-y-auto rounded-t-2xl p-5',
}

/** Accessible modal (focus trap, Esc, scroll lock). `side` turns it into a sheet for mobile menus and filters. */
export default function Dialog({
  open,
  onOpenChange,
  title,
  description,
  side = 'center',
  children,
  hideTitle = false,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  side?: Side
  children?: ReactNode
  hideTitle?: boolean
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-50 bg-black/40" />
        <RadixDialog.Content
          className={cx('fixed z-50 bg-white text-ink shadow-xl focus:outline-none', PANEL[side])}
        >
          <div className="flex items-start justify-between gap-4">
            <RadixDialog.Title className={cx('text-lg font-semibold', hideTitle && 'sr-only')}>{title}</RadixDialog.Title>
            <RadixDialog.Close asChild>
              <button type="button" aria-label="Close" className="-m-1 rounded-md p-1 text-muted transition-colors hover:bg-surface hover:text-ink">
                <X className="size-5" aria-hidden />
              </button>
            </RadixDialog.Close>
          </div>
          {description ? (
            <RadixDialog.Description className="mt-2 text-sm text-muted">{description}</RadixDialog.Description>
          ) : (
            <RadixDialog.Description className="sr-only">{title}</RadixDialog.Description>
          )}
          <div className="mt-4">{children}</div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}

/** A yes/no confirmation in a dialog; replaces `window.confirm`. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  danger = false,
  loading = false,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: string
  confirmLabel: string
  danger?: boolean
  loading?: boolean
  onConfirm: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={title} description={description}>
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={() => onOpenChange(false)}>
          Cancel
        </Button>
        <Button variant={danger ? 'danger' : 'primary'} loading={loading} onClick={onConfirm}>
          {confirmLabel}
        </Button>
      </div>
    </Dialog>
  )
}
