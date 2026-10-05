import { useMemo, useState } from 'react'
import { LEDGER_LABELS, LEDGER_TYPES, type RevenueReport } from '../api/revenue'
import { formatCents } from '../lib'
import { SERIES_COLORS } from './seriesColors'

const W = 720
const H = 260
const PAD = { top: 12, right: 8, bottom: 28, left: 52 }

/** Stacked bars of revenue per period. Refunds (negative) hang below the baseline. Hover or focus a bar for its figures. */
export default function StackedBars({ series, groupBy }: { series: RevenueReport['series']; groupBy: RevenueReport['groupBy'] }) {
  const [active, setActive] = useState<number | null>(null)

  const { maxPos, minNeg, ticks } = useMemo(() => {
    let pos = 0
    let neg = 0
    for (const b of series) {
      let p = 0
      let n = 0
      for (const t of LEDGER_TYPES) {
        if (b.byType[t] >= 0) p += b.byType[t]
        else n += b.byType[t]
      }
      pos = Math.max(pos, p)
      neg = Math.min(neg, n)
    }
    // Round steps (1, 2 or 5 times a power of ten) with the baseline on a tick, so every label is a round number.
    const step = niceStep((Math.max(pos, 100) - neg) / 4)
    const top = Math.ceil(Math.max(pos, 100) / step) * step
    const bottom = Math.floor(neg / step) * step
    return { maxPos: top, minNeg: bottom, ticks: Array.from({ length: Math.round((top - bottom) / step) + 1 }, (_, i) => bottom + step * i) }
  }, [series])

  const innerW = W - PAD.left - PAD.right
  const innerH = H - PAD.top - PAD.bottom
  const y = (v: number) => PAD.top + innerH * (1 - (v - minNeg) / (maxPos - minNeg))
  const slot = innerW / Math.max(series.length, 1)
  const barW = Math.max(2, Math.min(40, slot - 2))
  const labelEvery = Math.max(1, Math.ceil(series.length / 8))
  const hovered = active === null ? null : series[active]

  return (
    <div className="relative">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Revenue per ${groupBy}, stacked by source. Use "Show as table" for the figures.`} className="w-full">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} stroke="#e5e7eb" strokeWidth={t === 0 ? 1.5 : 1} />
            <text x={PAD.left - 8} y={y(t) + 4} textAnchor="end" fontSize="11" fill="#6b7280">{formatCents(t)}</text>
          </g>
        ))}
        {series.map((b, i) => {
          const x = PAD.left + slot * i + (slot - barW) / 2
          let up = 0
          let down = 0
          return (
            <g key={b.bucket} onMouseEnter={() => setActive(i)} onMouseLeave={() => setActive(null)} onFocus={() => setActive(i)} onBlur={() => setActive(null)} tabIndex={0} aria-label={`${b.bucket}: ${formatCents(b.netCents)}`}>
              <rect x={PAD.left + slot * i} y={PAD.top} width={slot} height={innerH} fill={active === i ? '#f6f7f9' : 'transparent'} />
              {LEDGER_TYPES.map((t) => {
                const v = b.byType[t]
                if (v === 0) return null
                const from = v > 0 ? up : down
                const to = from + v
                if (v > 0) up = to
                else down = to
                const top = y(Math.max(from, to))
                const height = Math.max(0, y(Math.min(from, to)) - top - 2) // 2px gap between segments
                return <rect key={t} x={x} y={top} width={barW} height={height} rx={3} fill={SERIES_COLORS[t]} />
              })}
              {i % labelEvery === 0 && (
                <text x={x + barW / 2} y={H - 8} textAnchor="middle" fontSize="11" fill="#6b7280">{shortLabel(b.bucket, groupBy)}</text>
              )}
            </g>
          )
        })}
      </svg>
      {hovered && (
        <div role="tooltip" className="pointer-events-none absolute right-2 top-2 rounded-lg border border-line bg-white p-3 text-xs shadow-lg">
          <p className="font-semibold text-ink">{longLabel(hovered.bucket, groupBy)}</p>
          {LEDGER_TYPES.map((t) => (
            <p key={t} className="mt-1 flex items-center gap-2 text-ink">
              <span aria-hidden className="size-2.5 rounded-sm" style={{ background: SERIES_COLORS[t] }} />
              <span className="flex-1 text-muted">{LEDGER_LABELS[t]}</span>
              <span className="tabular-nums">{formatCents(hovered.byType[t])}</span>
            </p>
          ))}
          <p className="mt-1.5 flex justify-between border-t border-line pt-1.5 font-semibold text-ink"><span>Net</span><span className="tabular-nums">{formatCents(hovered.netCents)}</span></p>
        </div>
      )}
    </div>
  )
}

/** The smallest of 1, 2 or 5 times a power of ten that is at least `raw`. */
function niceStep(raw: number): number {
  const pow = 10 ** Math.floor(Math.log10(raw))
  const n = raw / pow
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * pow
}

function shortLabel(bucket: string, groupBy: string) {
  const d = new Date(`${bucket}T00:00:00`)
  return groupBy === 'month'
    ? d.toLocaleDateString('en-US', { month: 'short' })
    : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
}

function longLabel(bucket: string, groupBy: string) {
  const d = new Date(`${bucket}T00:00:00`)
  if (groupBy === 'month') return d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' })
  const base = d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' })
  return groupBy === 'week' ? `Week of ${base}` : base
}
