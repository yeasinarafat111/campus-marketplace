import * as RadixDialog from '@radix-ui/react-dialog'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { ExternalLink, ListChecks, Loader2, SendHorizontal, Sparkles, Tag, TriangleAlert, X } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router-dom'
import {
  MAX_HISTORY,
  MAX_NEED,
  MAX_QUESTION,
  MAX_TURN_TEXT,
  VERDICT_LABELS,
  askAssistant,
  type AssistantMode,
  type AssistantResponse,
  type AssistantTurn,
  type Verdict,
} from '../api/assistant'
import { parseApiError } from '../api/errors'
import type { Listing } from '../api/listings'
import { useAuth } from '../hooks/useAuth'
import { useKeyboardInset } from '../hooks/useKeyboardInset'
import { usePublicConfig } from '../hooks/usePublicConfig'
import { cx } from '../lib'
import { priceCheckLinks } from '../priceLinks'
import { CONDITION_LABELS } from '../api/listings'
import Badge, { type BadgeTone } from './ui/Badge'
import Button from './ui/Button'
import { Input } from './ui/Input'

type Entry =
  | { id: number; kind: 'user'; text: string }
  | { id: number; kind: 'answer'; mode: AssistantMode; response: AssistantResponse }
  // productName: set when Gemini is out of quota, so the price links still show.
  | { id: number; kind: 'notice'; tone: 'warning' | 'error'; text: string; productName?: string }

/** Omit over each member of a union (plain Omit would merge them). */
type NewEntry = Entry extends infer E ? (E extends Entry ? Omit<E, 'id'> : never) : never

const VERDICT_TONES: Record<Verdict, BadgeTone> = {
  GOOD_DEAL: 'success',
  FAIR: 'neutral',
  OVERPRICED: 'danger',
  NOT_ENOUGH_DATA: 'muted',
}

const isWebUrl = (url: string) => /^https?:\/\//i.test(url)

/**
 * "Ask AI" on the listing page: a sparkle button that opens a bottom sheet (phones) or a side panel (desktop) with
 * Full specs, Is it a good buy? (when the server enables it) and a chat. The conversation lives only in this
 * component; nothing is stored. Answers are plain text: rendered as text with line breaks, never as HTML.
 */
export default function AskAiButton({ listing, className }: { listing: Listing; className?: string }) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  const config = usePublicConfig()
  const [open, setOpen] = useState(false)

  if (!config.data?.assistantEnabled) return null
  if (!isAuthenticated) {
    return (
      <Link
        to="/login"
        state={{ from: location }}
        className={cx('inline-flex min-h-11 items-center gap-1.5 text-sm font-semibold text-navy underline-offset-2 hover:underline', className)}
      >
        <Sparkles className="size-4" aria-hidden />
        Log in to ask AI about this item
      </Link>
    )
  }
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)} className={cx('h-11 border-navy/30 text-navy', className)}>
        <Sparkles className="size-4" aria-hidden />
        Ask AI
      </Button>
      <AskAiPanel
        listing={listing}
        open={open}
        onOpenChange={setOpen}
        verdictEnabled={config.data.assistantVerdictEnabled}
        bdtPerUsd={config.data.bdtPerUsd}
      />
    </>
  )
}

function AskAiPanel({
  listing,
  open,
  onOpenChange,
  verdictEnabled,
  bdtPerUsd,
}: {
  listing: Listing
  open: boolean
  onOpenChange: (open: boolean) => void
  verdictEnabled: boolean
  bdtPerUsd: number
}) {
  const queryClient = useQueryClient()
  const keyboard = useKeyboardInset(open)
  const [entries, setEntries] = useState<Entry[]>([])
  const [need, setNeed] = useState('')
  const [question, setQuestion] = useState('')
  const nextId = useRef(1)
  const bottomRef = useRef<HTMLDivElement>(null)

  const add = (entry: NewEntry) =>
    setEntries((list) => [...list, { ...entry, id: nextId.current++ } as Entry])

  const ask = useMutation({
    mutationFn: (vars: { mode: AssistantMode; question?: string; need?: string; history?: AssistantTurn[] }) =>
      askAssistant(listing.id, vars),
    onSuccess: (response, vars) => {
      if (response.unavailable) add({ kind: 'notice', tone: 'warning', text: response.answer, productName: response.productName ?? listing.title })
      else add({ kind: 'answer', mode: vars.mode, response })
    },
    onError: (error) => {
      const { status, message } = parseApiError(error)
      if (status === 429) {
        const wait = isAxiosError(error) ? error.response?.headers['retry-after'] : undefined
        add({ kind: 'notice', tone: 'warning', text: wait && !/try again in/i.test(message) ? `${message} Try again in ${wait} seconds.` : message })
      } else if (status === 503) {
        add({ kind: 'notice', tone: 'warning', text: message })
        // Switched off (or the price check is): refresh the switches so the button or action disappears.
        void queryClient.invalidateQueries({ queryKey: ['public-config'] })
      } else if (status === 400) {
        add({ kind: 'notice', tone: 'error', text: 'That question is too long. Keep it under 500 characters.' })
      } else if (isAxiosError(error) && error.code === 'ECONNABORTED') {
        add({ kind: 'notice', tone: 'warning', text: 'The assistant is taking too long. Please try again.' })
      } else {
        add({ kind: 'notice', tone: 'error', text: message })
      }
    },
  })

  // Keep the newest message in view.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
  }, [entries.length, ask.isPending])

  /** Previous successful turns for a follow-up, oldest first, at most six. */
  const history = (): AssistantTurn[] => {
    const turns: AssistantTurn[] = []
    for (const e of entries) {
      if (e.kind === 'user') turns.push({ role: 'user', text: e.text.slice(0, MAX_TURN_TEXT) })
      else if (e.kind === 'answer') turns.push({ role: 'assistant', text: e.response.answer.slice(0, MAX_TURN_TEXT) })
    }
    return turns.slice(-MAX_HISTORY)
  }

  const trimmedNeed = need.trim()

  const runSpecs = () => {
    add({ kind: 'user', text: trimmedNeed ? `Full specs, for: ${trimmedNeed}` : 'Full specs' })
    ask.mutate({ mode: 'SPECS', need: trimmedNeed || undefined })
  }

  const runVerdict = () => {
    add({ kind: 'user', text: 'Is it a good buy?' })
    ask.mutate({ mode: 'VERDICT' })
  }

  const send = (e: FormEvent) => {
    e.preventDefault()
    const q = question.trim()
    if (!q || ask.isPending) return
    const previous = history()
    add({ kind: 'user', text: q })
    setQuestion('')
    ask.mutate({ mode: 'ASK', question: q, need: trimmedNeed || undefined, history: previous.length ? previous : undefined })
  }

  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-50 bg-black/40" />
        <RadixDialog.Content
          style={keyboard ? { bottom: keyboard, maxHeight: `calc(100dvh - ${keyboard}px - 0.5rem)` } : undefined}
          className={cx(
            'fixed z-50 flex flex-col bg-white text-ink shadow-xl focus:outline-none',
            // Phones: a bottom sheet. Desktop: a panel on the right, full height.
            'inset-x-0 bottom-0 max-h-[88dvh] rounded-t-2xl',
            'lg:inset-x-auto lg:inset-y-0 lg:right-0 lg:max-h-none lg:w-[440px] lg:rounded-none',
          )}
        >
          <div className="flex items-start justify-between gap-3 border-b border-line px-4 py-3">
            <div className="min-w-0">
              <RadixDialog.Title className="flex items-center gap-2 text-lg font-semibold text-navy">
                <Sparkles className="size-5" aria-hidden />
                Ask AI
              </RadixDialog.Title>
              <RadixDialog.Description className="truncate text-sm text-muted">About: {listing.title}</RadixDialog.Description>
            </div>
            <RadixDialog.Close asChild>
              <button type="button" aria-label="Close" className="-m-1 rounded-md p-2 text-muted transition-colors hover:bg-surface hover:text-ink">
                <X className="size-5" aria-hidden />
              </button>
            </RadixDialog.Close>
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 py-4" aria-live="polite">
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" onClick={runSpecs} disabled={ask.isPending} className="h-11">
                <ListChecks className="size-4" aria-hidden />
                Full specs
              </Button>
              {verdictEnabled && (
                <Button variant="secondary" onClick={runVerdict} disabled={ask.isPending} className="h-11">
                  <Tag className="size-4" aria-hidden />
                  Is it a good buy?
                </Button>
              )}
            </div>
            <label htmlFor="ai-need" className="mt-4 mb-1.5 block text-sm font-medium text-ink">
              What will you use it for? <span className="font-normal text-muted">(optional)</span>
            </label>
            <Input
              id="ai-need"
              value={need}
              maxLength={MAX_NEED}
              onChange={(e) => setNeed(e.target.value)}
              placeholder="e.g. CSE coursework, gaming, first-year physics"
              className="text-base sm:text-sm"
            />

            {entries.length === 0 && !ask.isPending && (
              <p className="mt-5 text-sm text-muted">
                Get the full specs, or ask anything about this item. English or Bangla both work.
              </p>
            )}

            <ol className="mt-5 space-y-3">
              {entries.map((e) => (
                <li key={e.id}>
                  {renderEntry(e)}
                  {((e.kind === 'answer' && e.mode === 'SPECS') || (e.kind === 'notice' && e.productName)) && (
                    <PriceLinks
                      listing={listing}
                      bdtPerUsd={bdtPerUsd}
                      productName={e.kind === 'answer' ? e.response.productName : e.productName}
                    />
                  )}
                </li>
              ))}
              {ask.isPending && (
                <li className="flex items-center gap-2 rounded-xl bg-surface px-3 py-2.5 text-sm text-muted" role="status">
                  <Loader2 className="size-4 animate-spin" aria-hidden />
                  Thinking…
                </li>
              )}
            </ol>
            <div ref={bottomRef} />
          </div>

          <form onSubmit={send} className="flex items-center gap-2 border-t border-line px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
            <label htmlFor="ai-question" className="sr-only">Ask a question about this item</label>
            <Input
              id="ai-question"
              value={question}
              maxLength={MAX_QUESTION}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder="Ask about this item…"
              autoComplete="off"
              enterKeyHint="send"
              // 16px on phones so iOS doesn't zoom the page when the field gets focus.
              className="h-11 min-w-0 flex-1 text-base sm:text-sm"
            />
            <Button type="submit" aria-label="Send" disabled={!question.trim() || ask.isPending} className="h-11 w-11 px-0">
              <SendHorizontal className="size-5" aria-hidden />
            </Button>
          </form>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}

function renderEntry(e: Entry) {
  if (e.kind === 'user') {
    return (
      <div className="ml-auto w-fit max-w-[85%] rounded-xl bg-navy px-3 py-2 text-sm whitespace-pre-line break-words text-white">
        {e.text}
      </div>
    )
  }
  if (e.kind === 'notice') {
    return (
      <div
        role="alert"
        className={cx(
          'flex items-start gap-2 rounded-xl px-3 py-2.5 text-sm break-words',
          e.tone === 'warning' ? 'bg-warning-soft text-warning' : 'bg-danger-soft text-danger',
        )}
      >
        <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
        <p className="min-w-0">{e.text}</p>
      </div>
    )
  }
  return <Answer response={e.response} />
}

/** Searches that open in a new tab. Built on the page; nothing is fetched from these sites. */
function PriceLinks({ listing, bdtPerUsd, productName }: { listing: Listing; bdtPerUsd: number; productName?: string | null }) {
  const links = priceCheckLinks({
    productName,
    title: listing.title,
    priceUsd: listing.price,
    condition: listing.itemCondition ? CONDITION_LABELS[listing.itemCondition] : null,
    bdtPerUsd,
  })
  return (
    <div className="mt-2">
      <p className="text-xs font-semibold text-muted">Check live prices</p>
      <div className="mt-1 flex flex-wrap gap-2">
        {links.map((l) => (
          <a
            key={l.label}
            href={l.href}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex min-h-9 items-center gap-1 rounded-lg border border-line bg-white px-3 text-xs font-semibold text-navy transition-colors hover:bg-navy-soft"
          >
            <ExternalLink className="size-3.5" aria-hidden />
            {l.label}
          </a>
        ))}
      </div>
    </div>
  )
}

function Answer({ response: r }: { response: AssistantResponse }) {
  return (
    <div className="max-w-full rounded-xl border border-line bg-white px-3 py-3 text-sm">
      {r.verdict && (
        <div className="mb-3 space-y-1.5">
          <Badge tone={VERDICT_TONES[r.verdict]}>{VERDICT_LABELS[r.verdict]}</Badge>
          {r.typicalPriceLabel && <p className="font-semibold text-ink">{r.typicalPriceLabel}</p>}
          {r.foundPrices.length > 0 && (
            <ul className="space-y-0.5 text-ink">
              {r.foundPrices.map((p, i) => (
                <li key={i} className="break-words">
                  {p.label}
                  <span className="text-muted">
                    {' '}· {p.condition || 'condition unknown'} ·{' '}
                    {isWebUrl(p.sourceUrl) ? (
                      <a href={p.sourceUrl} target="_blank" rel="noopener noreferrer" className="underline underline-offset-2 hover:text-navy">
                        {p.sourceTitle || 'source'}
                      </a>
                    ) : (
                      p.sourceTitle
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {r.conversionNote && <p className="text-xs text-muted">{r.conversionNote}</p>}
        </div>
      )}
      <p className="whitespace-pre-line break-words leading-relaxed text-ink">{r.answer}</p>
      {r.sources.length > 0 && (
        <div className="mt-3 border-t border-line pt-2">
          <p className="text-xs font-semibold text-muted">Sources</p>
          <ul className="mt-1 space-y-1">
            {r.sources.filter((s) => isWebUrl(s.url)).map((s, i) => (
              <li key={i}>
                <a
                  href={s.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex max-w-full items-center gap-1 text-xs font-medium text-navy underline-offset-2 hover:underline"
                >
                  <ExternalLink className="size-3 shrink-0" aria-hidden />
                  <span className="truncate">{s.title || s.url}</span>
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
      {r.disclaimer && <p className="mt-3 text-xs text-muted">{r.disclaimer}</p>}
    </div>
  )
}
