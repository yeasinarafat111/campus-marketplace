import client from './client'

export interface ConversationListing {
  /** Null once the listing has been deleted; the title and image are then the copies from the start. */
  id: number | null
  title: string
  imageUrl: string | null
  price: number | null
  status: string | null
}

export interface Conversation {
  id: number
  listing: ConversationListing
  counterparty: { id: number; fullName: string }
  lastMessage: { preview: string; createdAt: string; mine: boolean } | null
  lastMessageAt: string
  unreadCount: number
}

export interface ChatMessage {
  id: number
  conversationId: number
  senderId: number
  body: string
  createdAt: string
  /** When the other side saw it; null until then. */
  readAt: string | null
}

export interface MessagePage {
  /** Oldest first within the page. */
  messages: ChatMessage[]
  hasMore: boolean
}

/** Gets or creates the caller's conversation about a listing. */
export const openConversation = (listingId: number) =>
  client.post<Conversation>('/api/conversations', { listingId }).then((r) => r.data)

export const getInbox = () => client.get<Conversation[]>('/api/conversations').then((r) => r.data)

export const getMessages = (conversationId: number, params: { beforeId?: number; size?: number } = {}) =>
  client.get<MessagePage>(`/api/conversations/${conversationId}/messages`, { params }).then((r) => r.data)

export const sendMessage = (conversationId: number, body: string) =>
  client.post<ChatMessage>(`/api/conversations/${conversationId}/messages`, { body }).then((r) => r.data)

export const markRead = (conversationId: number) =>
  client.post<{ count: number }>(`/api/conversations/${conversationId}/read`).then((r) => r.data)

export const getUnreadCount = () =>
  client.get<{ count: number }>('/api/conversations/unread-count').then((r) => r.data)

export const chatKeys = {
  inbox: ['chat', 'inbox'] as const,
  unread: ['chat', 'unread'] as const,
  thread: (id: number) => ['chat', 'thread', id] as const,
}
