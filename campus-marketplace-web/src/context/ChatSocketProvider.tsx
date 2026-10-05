import { Client } from '@stomp/stompjs'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, type ReactNode } from 'react'
import { refreshAccessToken } from '../api/client'
import { chatKeys, type ChatMessage, type MessagePage } from '../api/chat'
import { accessTokenSecondsLeft, getAccessToken } from '../api/tokens'
import { useAuth } from '../hooks/useAuth'

const MIN_DELAY_MS = 1_000
const MAX_DELAY_MS = 30_000

/** The API's origin as a WebSocket URL: http -> ws, https -> wss. */
function socketUrl(): string {
  const base = new URL(import.meta.env.VITE_API_URL || window.location.origin)
  base.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
  base.pathname = '/ws'
  return base.toString()
}

/**
 * One WebSocket for the whole app while someone is logged in. REST is the source of truth; the socket only
 * delivers pushes, which are fed into the React Query caches, so every screen just reads the cache.
 * - It sends the access token in the STOMP CONNECT headers, and gets a fresh one before each reconnect.
 * - It reconnects with exponential backoff (1s, 2s, 4s ... 30s), resetting once connected.
 * - On every (re)connect it refetches the inbox, the unread count and any open thread, so messages sent while
 *   the connection was down show up.
 */
export function ChatSocketProvider({ children }: { children: ReactNode }) {
  const { isAuthenticated, user } = useAuth()
  const queryClient = useQueryClient()
  const userId = user?.id
  const attempts = useRef(0)

  useEffect(() => {
    if (!isAuthenticated || userId === undefined) return

    const client = new Client({
      brokerURL: socketUrl(),
      reconnectDelay: MIN_DELAY_MS,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      // Runs before every connection attempt: a reconnect may come long after the 15-minute access token.
      beforeConnect: async (c) => {
        // Only when the token is (nearly) expired; refreshing needlessly just adds a request.
        const left = accessTokenSecondsLeft()
        if (left === null || left < 60) await refreshAccessToken().catch(() => undefined)
        c.connectHeaders = { Authorization: `Bearer ${getAccessToken() ?? ''}` }
      },
      onConnect: () => {
        attempts.current = 0
        client.reconnectDelay = MIN_DELAY_MS

        client.subscribe('/user/queue/messages', (frame) => {
          const message: ChatMessage = JSON.parse(frame.body)
          addToThread(message)
          queryClient.invalidateQueries({ queryKey: chatKeys.inbox })
        })
        client.subscribe('/user/queue/unread', (frame) => {
          queryClient.setQueryData(chatKeys.unread, JSON.parse(frame.body))
        })
        client.subscribe('/user/queue/read', (frame) => {
          const receipt: { conversationId: number; readAt: string } = JSON.parse(frame.body)
          // The other side read everything I sent in this conversation.
          queryClient.setQueryData<MessagePage>(chatKeys.thread(receipt.conversationId), (page) =>
            page && {
              ...page,
              messages: page.messages.map((m) => (m.senderId === userId && !m.readAt ? { ...m, readAt: receipt.readAt } : m)),
            },
          )
        })

        // Catch up on anything missed while disconnected (and on the first connect, harmlessly).
        queryClient.invalidateQueries({ queryKey: ['chat'] })
      },
      onWebSocketClose: () => {
        attempts.current += 1
        client.reconnectDelay = Math.min(MIN_DELAY_MS * 2 ** attempts.current, MAX_DELAY_MS)
      },
    })

    function addToThread(message: ChatMessage) {
      queryClient.setQueryData<MessagePage>(chatKeys.thread(message.conversationId), (page) => {
        if (!page) return page // not open: the inbox refetch is enough
        if (page.messages.some((m) => m.id === message.id)) return page
        return { ...page, messages: [...page.messages, message] }
      })
    }

    client.activate()
    return () => {
      void client.deactivate()
    }
  }, [isAuthenticated, userId, queryClient])

  return <>{children}</>
}
