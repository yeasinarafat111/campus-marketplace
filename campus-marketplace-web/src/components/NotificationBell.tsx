import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as Menu from '@radix-ui/react-dropdown-menu'
import { Bell } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import {
  getUnreadNotifications,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationKeys,
  type AppNotification,
} from '../api/notifications'
import { cx, timeAgo } from '../lib'
import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from './ui/DropdownMenu'

/** The bell in the header: unread count, and the latest notifications in a dropdown. Kept live by the socket. */
export default function NotificationBell() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const unread = useQuery({ queryKey: notificationKeys.unread, queryFn: getUnreadNotifications, staleTime: 60_000 })
  const count = unread.data?.count ?? 0
  const list = useQuery({ queryKey: notificationKeys.list, queryFn: () => listNotifications({ size: 15 }), staleTime: 30_000 })

  const readOne = useMutation({
    mutationFn: markNotificationRead,
    onMutate: (id) => {
      queryClient.setQueryData<{ content: AppNotification[] } | undefined>(notificationKeys.list, (page) =>
        page && { ...page, content: page.content.map((n) => (n.id === id ? { ...n, read: true } : n)) })
      queryClient.setQueryData<{ count: number }>(notificationKeys.unread, (c) => ({ count: Math.max(0, (c?.count ?? 1) - 1) }))
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  })
  const readAll = useMutation({
    mutationFn: markAllNotificationsRead,
    onMutate: () => {
      queryClient.setQueryData<{ content: AppNotification[] } | undefined>(notificationKeys.list, (page) =>
        page && { ...page, content: page.content.map((n) => ({ ...n, read: true })) })
      queryClient.setQueryData(notificationKeys.unread, { count: 0 })
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  })

  const items = list.data?.content ?? []

  return (
    <DropdownMenu onOpenChange={(open) => open && queryClient.invalidateQueries({ queryKey: notificationKeys.list })}>
      <DropdownMenuTrigger
        aria-label={count > 0 ? `Notifications, ${count} unread` : 'Notifications'}
        className="relative rounded-lg p-2 text-white transition-colors hover:bg-white/10"
      >
        <Bell className="size-5" aria-hidden />
        {count > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold leading-4 text-white">
            {count > 99 ? '99+' : count}
          </span>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-[min(24rem,calc(100vw-1rem))] p-0">
        <div className="flex items-center justify-between border-b border-line px-3 py-2">
          <p className="text-sm font-semibold text-ink">Notifications</p>
          <Menu.Item
            disabled={count === 0}
            onSelect={(e) => { e.preventDefault(); readAll.mutate() }}
            className="min-h-9 cursor-pointer rounded-md px-2 text-xs font-semibold text-navy outline-none data-[disabled]:cursor-default data-[disabled]:text-muted data-[highlighted]:bg-surface"
          >
            Mark all read
          </Menu.Item>
        </div>
        <div className="max-h-[70vh] overflow-y-auto">
          {items.length === 0 && (
            <p className="px-4 py-8 text-center text-sm text-muted">{list.isPending ? 'Loading…' : "You're all caught up."}</p>
          )}
          {items.map((n) => (
            <Menu.Item
              key={n.id}
              onSelect={() => {
                if (!n.read) readOne.mutate(n.id)
                if (n.link) navigate(n.link)
              }}
              className={cx(
                'flex cursor-pointer gap-3 border-b border-line px-3 py-3 outline-none last:border-b-0 data-[highlighted]:bg-surface',
                !n.read && 'bg-navy-soft/50',
              )}
            >
              <span aria-hidden className={cx('mt-1.5 size-2 shrink-0 rounded-full', n.read ? 'bg-transparent' : 'bg-navy')} />
              <span className="min-w-0 flex-1">
                <span className={cx('block text-sm text-ink', !n.read && 'font-semibold')}>{n.title}</span>
                {n.body && <span className="mt-0.5 block text-xs text-muted">{n.body}</span>}
                <span className="mt-1 block text-[11px] text-muted">{timeAgo(n.createdAt)}</span>
              </span>
              {!n.read && <span className="sr-only">Unread</span>}
            </Menu.Item>
          ))}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
