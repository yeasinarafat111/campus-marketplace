/**
 * "Check live prices" links for the Ask AI panel: searches on Google AI Mode, Bikroy and Daraz that open in a new tab.
 * Built here, nothing is fetched or read back. The taka figure is only text in a search query (never charged), from
 * the server's approximate rate, rounded to the nearest ৳100.
 */

export interface PriceLinkInput {
  /** The product as the assistant identified it; falls back to `title` when missing or blank. */
  productName?: string | null
  title: string
  priceUsd: number
  /** e.g. "Good", "Like new"; null when the seller didn't say. */
  condition?: string | null
  bdtPerUsd: number
}

export interface PriceLink {
  label: string
  href: string
}

/** USD → BDT, rounded to the nearest 100 (half up). */
export function roundedBdt(usd: number, bdtPerUsd: number): number {
  return Math.round((usd * bdtPerUsd) / 100) * 100
}

const formatUsd = (usd: number) => (Number.isInteger(usd) ? String(usd) : usd.toFixed(2))

export function priceCheckQuery({ productName, title, priceUsd, condition, bdtPerUsd }: PriceLinkInput): string {
  const name = productName?.trim() || title.trim()
  const bdt = roundedBdt(priceUsd, bdtPerUsd).toLocaleString('en-US')
  const state = condition?.trim().toLowerCase() || 'used'
  return `${name} used price in Bangladesh. Is ৳${bdt} (about $${formatUsd(priceUsd)}) a good deal for a ${state} one?`
}

export function priceCheckLinks(input: PriceLinkInput): PriceLink[] {
  const name = encodeURIComponent(input.productName?.trim() || input.title.trim())
  return [
    // udm=50 opens Google's AI Mode.
    { label: 'Ask Google AI Mode', href: `https://www.google.com/search?udm=50&q=${encodeURIComponent(priceCheckQuery(input))}` },
    // Bikroy's own search box uses /search?query= (the /en/ads/bangladesh?query= form redirects to the home page).
    { label: 'Search Bikroy', href: `https://bikroy.com/search?query=${name}` },
    { label: 'Search Daraz', href: `https://www.daraz.com.bd/catalog/?q=${name}` },
  ]
}
