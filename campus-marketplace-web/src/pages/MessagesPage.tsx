import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, MessageCircle, Send } from 'lucide-react'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import {
  chatKeys, getInbox, getMessages, markRead, sendMessage,
  type ChatMessage, type Conversation, type MessagePage,
} from '../api/chat'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import Avatar from '../components/ui/Avatar'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import ImageWithFallback from '../components/ui/ImageWithFallback'
import PriceTag from '../components/ui/PriceTag'
import Skeleton from '../components/ui/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { cx, timeAgo } from '../lib'

/**
 * /messages: on desktop the inbox on the left and the open thread on the right; on a phone the list, then (once a
 * conversation is chosen) the thread on its own with a back arrow.
 */
export default function MessagesPage() {
  useDocumentTitle('Messages')
  const { id } = useParams()
  const openId = id ? Number(id) : null
  const inbox = useQuery({ queryKey: chatKeys.inbox, queryFn: getInbox, staleTime: 0 })

  return (
    <div className="mx-auto grid h-[calc(100dvh-9rem)] min-h-[28rem] max-w-5xl overflow-hidden rounded-xl border border-line md:grid-cols-[20rem_1fr]">
      <aside className={cx('flex min-h-0 flex-col border-line md:border-r', openId !== null && 'hidden md:flex')}>
        <h1 className="border-b border-line px-4 py-3 text-lg font-bold text-navy">Messages</h1>
        <InboxList inbox={inbox.data} loading={inbox.isPending} error={inbox.error} activeId={openId} />
      </aside>
      <section className={cx('flex min-h-0 flex-col', openId === null && 'hidden md:flex')}>
        {openId === null ? (
          <div className="m-auto p-6 text-center text-muted">
            <MessageCircle className="mx-auto mb-2 size-8" aria-hidden />
            Choose a conversation to read it.
          </div>
        ) : (
          <Thread key={openId} conversationId={openId} conversation={inbox.data?.find((c) => c.id === openId)} />
        )}
      </section>
    </div>
  )
}

function InboxList({ inbox, loading, error, activeId }: { inbox?: Conversation[]; loading: boolean; error: unknown; activeId: number | null }) {
  if (loading) {
    return <div className="space-y-3 p-4">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-14 w-full" />)}</div>
  }
  if (error) return <div className="p-4"><Alert kind="error">{parseApiError(error).message}</Alert></div>
  if (!inbox || inbox.length === 0) {
    return (
      <div className="p-4">
        <EmptyState icon={MessageCircle} title="No conversations yet">
          Open a listing and choose "Message seller" to start one.
        </EmptyState>
      </div>
    )
  }
  return (
    <ul className="min-h-0 flex-1 overflow-y-auto">
      {inbox.map((c) => (
        <li key={c.id}>
          <Link
            to={`/messages/${c.id}`}
            aria-current={c.id === activeId ? 'page' : undefined}
            className={cx('flex gap-3 border-b border-line px-4 py-3 transition-colors duration-150 hover:bg-surface', c.id === activeId && 'bg-navy-soft')}
          >
            <Avatar name={c.counterparty.fullName} />
            <div className="min-w-0 flex-1">
              <div className="flex items-baseline justify-between gap-2">
                <p className={cx('truncate text-sm', c.unreadCount > 0 ? 'font-bold text-ink' : 'font-semibold text-ink')}>{c.counterparty.fullName}</p>
                <span className="shrink-0 text-xs text-muted">{timeAgo(c.lastMessageAt)}</span>
              </div>
              <p className="truncate text-xs text-muted">{c.listing.title}</p>
              <div className="flex items-center justify-between gap-2">
                <p className={cx('truncate text-sm', c.unreadCount > 0 ? 'font-medium text-ink' : 'text-muted')}>
                  {c.lastMessage ? `${c.lastMessage.mine ? 'You: ' : ''}${c.lastMessage.preview}` : 'No messages yet'}
                </p>
                {c.unreadCount > 0 && (
                  <span aria-label={`${c.unreadCount} unread`} className="flex min-w-5 shrink-0 items-center justify-center rounded-full bg-navy px-1.5 text-xs font-semibold text-white">
                    {c.unreadCount}
                  </span>
                )}
              </div>
            </div>
          </Link>
        </li>
      ))}
    </ul>
  )
}

interface Pending {
  key: string
  body: string
  createdAt: string
  failed: boolean
}

function Thread({ conversationId, conversation }: { conversationId: number; conversation?: Conversation }) {
  const { user } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const me = user!.id
  const [pending, setPending] = useState<Pending[]>([])
  const [loadingOlder, setLoadingOlder] = useState(false)
  const scroller = useRef<HTMLDivElement>(null)
  const stickToBottom = useRef(true)

  const thread = useQuery({
    queryKey: chatKeys.thread(conversationId),
    queryFn: () => getMessages(conversationId, { size: 30 }),
    staleTime: 0,
  })
  const messages = useMemo(() => thread.data?.messages ?? [], [thread.data])

  // Mark what the other side sent as read whenever it is on screen, including messages that arrive live.
  const unreadFromThem = messages.filter((m) => m.senderId !== me && !m.readAt).length
  const read = useMutation({
    mutationFn: () => markRead(conversationId),
    onSuccess: (r) => {
      queryClient.setQueryData(chatKeys.unread, r)
      queryClient.setQueryData<MessagePage>(chatKeys.thread(conversationId), (page) =>
        page && { ...page, messages: page.messages.map((m) => (m.senderId !== me && !m.readAt ? { ...m, readAt: new Date().toISOString() } : m)) })
      queryClient.invalidateQueries({ queryKey: chatKeys.inbox })
    },
  })
  const markReadNow = read.mutate
  useEffect(() => {
    if (unreadFromThem > 0 && document.visibilityState === 'visible') markReadNow()
  }, [unreadFromThem, markReadNow])

  // Keep the newest message in view when new ones arrive, unless the reader scrolled up.
  useLayoutEffect(() => {
    const el = scroller.current
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight
  }, [messages.length, pending.length])

  const addToCache = useCallback((message: ChatMessage) => {
    queryClient.setQueryData<MessagePage>(chatKeys.thread(conversationId), (page) =>
      page && !page.messages.some((m) => m.id === message.id) ? { ...page, messages: [...page.messages, message] } : page)
  }, [queryClient, conversationId])

  async function submit(body: string, key: string = crypto.randomUUID()) {
    setPending((p) => (p.some((x) => x.key === key) ? p.map((x) => (x.key === key ? { ...x, failed: false } : x)) : [...p, { key, body, createdAt: new Date().toISOString(), failed: false }]))
    stickToBottom.current = true
    try {
      const saved = await sendMessage(conversationId, body)
      addToCache(saved)
      setPending((p) => p.filter((x) => x.key !== key))
      queryClient.invalidateQueries({ queryKey: chatKeys.inbox })
    } catch (e) {
      setPending((p) => p.map((x) => (x.key === key ? { ...x, failed: true } : x)))
      toast.error(parseApiError(e).message)
    }
  }

  async function loadOlder() {
    const first = messages[0]
    if (!first) return
    setLoadingOlder(true)
    stickToBottom.current = false
    try {
      const older = await getMessages(conversationId, { beforeId: first.id, size: 30 })
      queryClient.setQueryData<MessagePage>(chatKeys.thread(conversationId), (page) =>
        page && { hasMore: older.hasMore, messages: [...older.messages, ...page.messages] })
    } catch (e) {
      toast.error(parseApiError(e).message)
    } finally {
      setLoadingOlder(false)
    }
  }

  const lastMine = [...messages].reverse().find((m) => m.senderId === me)

  return (
    <>
      <header className="flex items-center gap-3 border-b border-line px-3 py-3 md:px-4">
        <button type="button" onClick={() => navigate('/messages')} aria-label="Back to conversations" className="rounded-lg p-2 transition-colors hover:bg-surface md:hidden">
          <ArrowLeft className="size-5" aria-hidden />
        </button>
        {conversation ? (
          <>
            <Avatar name={conversation.counterparty.fullName} />
            <p className="min-w-0 flex-1 truncate font-semibold text-ink">{conversation.counterparty.fullName}</p>
          </>
        ) : (
          <Skeleton className="h-9 w-40" />
        )}
      </header>

      {conversation && <ListingContext conversation={conversation} />}

      <div
        ref={scroller}
        onScroll={(e) => {
          const el = e.currentTarget
          stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
        }}
        className="min-h-0 flex-1 space-y-1 overflow-y-auto px-3 py-4 md:px-4"
      >
        {thread.isPending && <Skeleton className="h-16 w-2/3" />}
        {thread.error && <Alert kind="error">{parseApiError(thread.error).message}</Alert>}
        {thread.data?.hasMore && (
          <div className="pb-2 text-center">
            <Button variant="ghost" size="sm" loading={loadingOlder} onClick={loadOlder}>Load earlier messages</Button>
          </div>
        )}
        {thread.data && messages.length === 0 && pending.length === 0 && (
          <p className="py-10 text-center text-sm text-muted">No messages yet. Say hello.</p>
        )}
        <Bubbles messages={messages} pending={pending} me={me} lastMineId={lastMine?.id} onRetry={(p) => submit(p.body, p.key)} />
      </div>

      <Composer onSend={(body) => submit(body)} />
    </>
  )
}

function ListingContext({ conversation }: { conversation: Conversation }) {
  const l = conversation.listing
  const body = (
    <>
      <ImageWithFallback src={l.imageUrl} alt={l.title} className="size-12 shrink-0 rounded-lg" />
      <div className="min-w-0">
        <p className="truncate text-sm font-semibold text-ink">{l.title}</p>
        {l.id === null ? (
          <p className="text-xs text-muted">This listing has been deleted</p>
        ) : (
          l.price !== null && <PriceTag price={l.price} size="sm" />
        )}
      </div>
    </>
  )
  return l.id === null ? (
    <div className="flex items-center gap-3 border-b border-line bg-surface px-4 py-2">{body}</div>
  ) : (
    <Link to={`/listings/${l.id}`} className="flex items-center gap-3 border-b border-line bg-surface px-4 py-2 transition-colors hover:bg-gray-100">
      {body}
    </Link>
  )
}

const dayKey = (iso: string) => new Date(iso).toDateString()
const time = (iso: string) => new Date(iso).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })

function dayLabel(iso: string): string {
  const d = new Date(iso)
  const today = new Date()
  const yesterday = new Date()
  yesterday.setDate(today.getDate() - 1)
  if (d.toDateString() === today.toDateString()) return 'Today'
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday'
  return d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })
}

type Item = { key: string; senderId: number; body: string; createdAt: string; readAt: string | null; id?: number; pending?: Pending }

/** Date separators, and consecutive messages from one person within five minutes grouped into one cluster. */
function Bubbles({ messages, pending, me, lastMineId, onRetry }: { messages: ChatMessage[]; pending: Pending[]; me: number; lastMineId?: number; onRetry: (p: Pending) => void }) {
  const items: Item[] = [
    ...messages.map((m) => ({ key: `m${m.id}`, senderId: m.senderId, body: m.body, createdAt: m.createdAt, readAt: m.readAt, id: m.id })),
    ...pending.map((p) => ({ key: `p${p.key}`, senderId: me, body: p.body, createdAt: p.createdAt, readAt: null, pending: p })),
  ]
  const out: React.ReactNode[] = []
  items.forEach((item, i) => {
    const prev = items[i - 1]
    const next = items[i + 1]
    const mine = item.senderId === me
    if (!prev || dayKey(prev.createdAt) !== dayKey(item.createdAt)) {
      out.push(<div key={`d${item.key}`} className="py-3 text-center text-xs font-medium text-muted">{dayLabel(item.createdAt)}</div>)
    }
    const gap = (a?: Item, b?: Item) => !a || !b || a.senderId !== b.senderId || dayKey(a.createdAt) !== dayKey(b.createdAt)
      || Math.abs(new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()) > 5 * 60_000
    const startsGroup = gap(prev, item)
    const endsGroup = gap(item, next)
    out.push(
      <div key={item.key} className={cx('flex flex-col', mine ? 'items-end' : 'items-start', startsGroup && 'pt-2')}>
        <p
          className={cx(
            'max-w-[80%] whitespace-pre-wrap break-words rounded-2xl px-3.5 py-2 text-sm',
            mine ? 'bg-navy text-white' : 'bg-gray-100 text-ink',
            item.pending?.failed && 'opacity-60',
          )}
        >
          {item.body}
        </p>
        {endsGroup && (
          <p className="mt-0.5 flex items-center gap-2 text-xs text-muted">
            {item.pending ? (item.pending.failed ? <span className="text-danger">Not sent.</span> : 'Sending…') : time(item.createdAt)}
            {item.pending?.failed && (
              <button type="button" onClick={() => onRetry(item.pending!)} className="font-semibold text-navy underline underline-offset-2">Retry</button>
            )}
            {mine && item.id !== undefined && item.id === lastMineId && item.readAt && <span>Seen</span>}
          </p>
        )}
      </div>,
    )
  })
  return <>{out}</>
}

/** Enter sends, Shift+Enter adds a line, and the box grows with what's typed (up to a limit). */
function Composer({ onSend }: { onSend: (body: string) => void }) {
  const [value, setValue] = useState('')
  const box = useRef<HTMLTextAreaElement>(null)

  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 128)}px`
  }, [value])

  function send() {
    const body = value.trim()
    if (!body) return
    onSend(body)
    setValue('')
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      send()
    }
  }

  return (
    <form onSubmit={(e) => { e.preventDefault(); send() }} className="flex items-end gap-2 border-t border-line p-3">
      <textarea
        ref={box}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={onKeyDown}
        rows={1}
        maxLength={2000}
        placeholder="Write a message"
        aria-label="Message"
        className="max-h-32 min-h-10 flex-1 resize-none rounded-lg border border-line px-3 py-2 text-sm text-ink placeholder:text-muted hover:border-gray-300"
      />
      <Button type="submit" aria-label="Send" disabled={!value.trim()} className="size-10 !px-0">
        <Send className="size-4" aria-hidden />
      </Button>
    </form>
  )
}
