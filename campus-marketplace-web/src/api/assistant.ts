import client from './client'

/** Feature switches the server tells the frontend about (public, no login). */
export interface PublicConfig {
  assistantEnabled: boolean
  assistantVerdictEnabled: boolean
  assistantWebSearch: boolean
  /** Approximate taka per dollar, for showing a price in BDT in search links. */
  bdtPerUsd: number
}

export const getPublicConfig = () => client.get<PublicConfig>('/api/config').then((r) => r.data)

export type AssistantMode = 'SPECS' | 'VERDICT' | 'ASK'
export type Verdict = 'GOOD_DEAL' | 'FAIR' | 'OVERPRICED' | 'NOT_ENOUGH_DATA'

export interface AssistantTurn {
  role: 'user' | 'assistant'
  text: string
}

export interface AssistantRequest {
  mode: AssistantMode
  need?: string
  question?: string
  history?: AssistantTurn[]
}

export interface FoundPrice {
  amountBdt: number
  amountUsd: number
  condition: string
  sourceTitle: string
  sourceUrl: string
  /** "৳12,500 (≈ $102)", formatted by the server. */
  label: string
}

export interface AssistantResponse {
  /** Plain text with line breaks. Never render as HTML. */
  answer: string
  verdict: Verdict | null
  sources: { title: string; url: string }[]
  disclaimer: string | null
  cached: boolean
  /** Gemini refused or failed: `answer` is a message to show as is. */
  unavailable: boolean
  listingPriceUsd: number | null
  medianUsd: number | null
  bdtPerUsd: number | null
  typicalPriceLabel: string | null
  conversionNote: string | null
  foundPrices: FoundPrice[]
  /** The exact product the assistant identified (SPECS), otherwise the listing title. */
  productName: string | null
}

/** The server keeps at most this many previous turns; so does the panel. */
export const MAX_HISTORY = 6
export const MAX_NEED = 200
export const MAX_QUESTION = 500
export const MAX_TURN_TEXT = 2000

export const askAssistant = (listingId: number, request: AssistantRequest) =>
  client
    // Longer than the server's Gemini budget (45 s; a 5xx is retried only if it came back within 10 s), so the
    // server's friendly answer always arrives first.
    .post<AssistantResponse>(`/api/listings/${listingId}/assistant`, request, { timeout: 60_000 })
    .then((r) => r.data)

export const VERDICT_LABELS: Record<Verdict, string> = {
  GOOD_DEAL: 'Good deal',
  FAIR: 'Fair price',
  OVERPRICED: 'Overpriced',
  NOT_ENOUGH_DATA: 'Not enough data',
}
