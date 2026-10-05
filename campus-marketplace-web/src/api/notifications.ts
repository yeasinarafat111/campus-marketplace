import client from './client'
import type { Page } from './listings'

export type NotificationType = 'RESERVED' | 'RESERVATION_EXPIRING' | 'PURCHASED' | 'CASH_HANDOVER_CONFIRMED' | 'REPORT_RESOLVED'

export interface AppNotification {
  id: number
  type: NotificationType
  title: string
  body: string | null
  /** A path inside the app, or null. */
  link: string | null
  read: boolean
  createdAt: string
}

/** What arrives on /user/queue/notifications. `notification` is null when only the unread count changed. */
export interface NotificationPush {
  notification: AppNotification | null
  unread: number
}

export const notificationKeys = {
  all: ['notifications'] as const,
  list: ['notifications', 'list'] as const,
  unread: ['notifications', 'unread'] as const,
}

export const listNotifications = (params: { page?: number; size?: number } = {}) =>
  client.get<Page<AppNotification>>('/api/notifications', { params }).then((r) => r.data)

export const getUnreadNotifications = () =>
  client.get<{ count: number }>('/api/notifications/unread-count').then((r) => r.data)

export const markNotificationRead = (id: number) => client.post(`/api/notifications/${id}/read`).then(() => undefined)
export const markAllNotificationsRead = () => client.post('/api/notifications/read-all').then(() => undefined)
