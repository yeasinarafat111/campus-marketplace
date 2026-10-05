import * as Menu from '@radix-ui/react-dropdown-menu'
import type { ComponentProps, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { cx } from '../../lib'

export const DropdownMenu = Menu.Root
export const DropdownMenuTrigger = Menu.Trigger

export function DropdownMenuContent({ className, children, ...props }: ComponentProps<typeof Menu.Content>) {
  return (
    <Menu.Portal>
      <Menu.Content
        align="end"
        sideOffset={8}
        className={cx('z-50 min-w-56 rounded-xl border border-line bg-white p-1.5 text-sm shadow-lg', className)}
        {...props}
      >
        {children}
      </Menu.Content>
    </Menu.Portal>
  )
}

const itemClasses =
  'flex w-full cursor-pointer select-none items-center gap-2.5 rounded-lg px-2.5 py-2 text-ink outline-none transition-colors data-[highlighted]:bg-surface'

export function DropdownMenuLink({ to, icon, children }: { to: string; icon?: ReactNode; children: ReactNode }) {
  return (
    <Menu.Item asChild>
      <Link to={to} className={itemClasses}>
        {icon}
        {children}
      </Link>
    </Menu.Item>
  )
}

export function DropdownMenuItem({ icon, children, ...props }: ComponentProps<typeof Menu.Item> & { icon?: ReactNode }) {
  return (
    <Menu.Item className={itemClasses} {...props}>
      {icon}
      {children}
    </Menu.Item>
  )
}

export const DropdownMenuLabel = ({ children }: { children: ReactNode }) => <div className="px-2.5 py-2">{children}</div>
export const DropdownMenuSeparator = () => <Menu.Separator className="my-1.5 h-px bg-line" />
