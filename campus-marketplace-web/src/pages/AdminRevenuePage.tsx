import { useQuery } from '@tanstack/react-query'
import { Download } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import {
  LEDGER_LABELS, LEDGER_TYPES, downloadRevenueCsv, getRevenue, getRevenueEntries,
  type GroupBy, type LedgerType, type RevenueReport,
} from '../api/revenue'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import Pagination from '../components/Pagination'
import { SERIES_COLORS } from '../components/seriesColors'
import StackedBars from '../components/StackedBars'
import Button from '../components/ui/Button'
import { Input, Select } from '../components/ui/Input'
import Skeleton from '../components/ui/Skeleton'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { cx, formatCents, formatDateTime } from '../lib'

const isoDate = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const daysAgo = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return isoDate(d) }

/** Platform revenue for a date range: tiles, a stacked chart by source, and the ledger history with a CSV export. */
export default function AdminRevenuePage() {
  useDocumentTitle('Revenue')
  const [from, setFrom] = useState(daysAgo(29))
  const [to, setTo] = useState(daysAgo(0))
  const [groupBy, setGroupBy] = useState<GroupBy>('day')
  const [type, setType] = useState<LedgerType | ''>('')
  const [page, setPage] = useState(0)
  const [asTable, setAsTable] = useState(false)
  const [exporting, setExporting] = useState(false)
  const rangeOk = from <= to

  const report = useQuery({ queryKey: ['revenue', from, to, groupBy], queryFn: () => getRevenue({ from, to, groupBy }), enabled: rangeOk })
  const entries = useQuery({
    queryKey: ['revenue-entries', from, to, type, page],
    queryFn: () => getRevenueEntries({ from, to, type: type || undefined, page, size: 15 }),
    enabled: rangeOk,
  })

  const preset = (days: number) => { setFrom(daysAgo(days - 1)); setTo(daysAgo(0)); setPage(0) }
  async function exportCsv() {
    setExporting(true)
    try { await downloadRevenueCsv({ from, to, type: type || undefined }) } catch (e) { toast.error(parseApiError(e).message) } finally { setExporting(false) }
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-navy">Revenue</h1>
        <p className="mt-1 text-sm text-muted">Before Stripe processing fees. Refunds are subtracted.</p>
      </div>

      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => e.preventDefault()} aria-label="Date range">
        <label className="text-sm font-medium text-ink">From<Input type="date" value={from} max={to} onChange={(e) => { setFrom(e.target.value); setPage(0) }} className="mt-1 w-40" /></label>
        <label className="text-sm font-medium text-ink">To<Input type="date" value={to} min={from} onChange={(e) => { setTo(e.target.value); setPage(0) }} className="mt-1 w-40" /></label>
        <div className="flex gap-2">
          {[7, 30, 90].map((d) => <Button key={d} type="button" variant="secondary" size="sm" onClick={() => preset(d)}>Last {d} days</Button>)}
        </div>
        <label className="text-sm font-medium text-ink">Group by
          <Select value={groupBy} onChange={(e) => setGroupBy(e.target.value as GroupBy)} className="mt-1 w-32">
            <option value="day">Day</option><option value="week">Week</option><option value="month">Month</option>
          </Select>
        </label>
      </form>
      {!rangeOk && <Alert kind="error">The start date must not be after the end date.</Alert>}
      {report.error && <Alert kind="error">{parseApiError(report.error).message}</Alert>}

      {report.isPending && rangeOk ? <Skeleton className="h-64 w-full" /> : report.data && (
        <>
          <Tiles report={report.data} />
          <section aria-labelledby="chart-heading" className="rounded-xl border border-line p-4 sm:p-5">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h2 id="chart-heading" className="text-lg font-bold text-navy">Revenue by source, per {groupBy}</h2>
              <Button variant="secondary" size="sm" onClick={() => setAsTable((v) => !v)} aria-pressed={asTable}>{asTable ? 'Show as chart' : 'Show as table'}</Button>
            </div>
            <ul className="mb-3 flex flex-wrap gap-x-5 gap-y-1 text-sm">
              {LEDGER_TYPES.map((t) => (
                <li key={t} className="flex items-center gap-2 text-ink">
                  <span aria-hidden className="size-3 rounded-sm" style={{ background: SERIES_COLORS[t] }} />
                  {LEDGER_LABELS[t]} <span className="tabular-nums text-muted">{formatCents(report.data.totals[t])}</span>
                </li>
              ))}
            </ul>
            {asTable ? <SeriesTable report={report.data} /> : <StackedBars series={report.data.series} groupBy={report.data.groupBy} />}
          </section>
        </>
      )}

      <section aria-labelledby="history-heading">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <h2 id="history-heading" className="text-lg font-bold text-navy">History</h2>
          <div className="flex items-end gap-2">
            <label className="text-sm font-medium text-ink">Source
              <Select value={type} onChange={(e) => { setType(e.target.value as LedgerType | ''); setPage(0) }} className="mt-1 w-48">
                <option value="">All sources</option>
                {LEDGER_TYPES.map((t) => <option key={t} value={t}>{LEDGER_LABELS[t]}</option>)}
              </Select>
            </label>
            <Button variant="secondary" loading={exporting} disabled={!rangeOk} onClick={exportCsv}><Download className="size-4" aria-hidden />Export CSV</Button>
          </div>
        </div>
        {entries.error && <Alert kind="error">{parseApiError(entries.error).message}</Alert>}
        <div className="relative overflow-x-auto rounded-xl border border-line">
          <table className="min-w-full divide-y divide-line text-sm">
            <thead className="bg-surface">
              <tr>{['Date', 'Source', 'Listing', 'User', 'Amount'].map((h, i) => <th key={h} className={cx('px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted', i === 4 && 'text-right')}>{h}</th>)}</tr>
            </thead>
            <tbody className="divide-y divide-line">
              {entries.data?.content.map((e) => (
                <tr key={e.id}>
                  <td className="whitespace-nowrap px-4 py-3">{formatDateTime(e.occurredAt)}</td>
                  <td className="px-4 py-3"><span className="inline-flex items-center gap-2"><span aria-hidden className="size-2.5 rounded-sm" style={{ background: SERIES_COLORS[e.type] }} />{LEDGER_LABELS[e.type]}</span></td>
                  <td className="px-4 py-3">{e.listing?.title ?? <span className="text-muted">deleted listing</span>}</td>
                  <td className="px-4 py-3">{e.user?.fullName ?? ''}<span className="block text-xs text-muted">{e.user?.email}</span></td>
                  <td className={cx('px-4 py-3 text-right font-medium tabular-nums', e.amountCents < 0 && 'text-danger')}>{formatCents(e.amountCents)}{e.amountCents < 0 && <span className="ml-1 text-xs font-normal">refund</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {entries.data && entries.data.content.length === 0 && <p className="mt-2 text-sm text-muted">No revenue in this range.</p>}
        {entries.data && <Pagination page={entries.data.page} totalPages={entries.data.totalPages} onChange={setPage} />}
      </section>
    </div>
  )
}

function Tiles({ report }: { report: RevenueReport }) {
  const tile = 'rounded-xl border border-line p-4'
  return (
    <section aria-label="Totals" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      <div className={`${tile} bg-navy-soft sm:col-span-2 lg:col-span-1`}>
        <p className="text-sm text-muted">Net revenue</p>
        <p className="mt-1 text-3xl font-bold tabular-nums text-navy">{formatCents(report.netCents)}</p>
        <p className="mt-1 text-xs text-muted">{report.from} to {report.to}</p>
      </div>
      {LEDGER_TYPES.map((t) => (
        <div key={t} className={tile}>
          <p className="flex items-center gap-2 text-sm text-muted"><span aria-hidden className="size-2.5 rounded-sm" style={{ background: SERIES_COLORS[t] }} />{LEDGER_LABELS[t]}</p>
          <p className="mt-1 text-2xl font-bold tabular-nums text-ink">{formatCents(report.totals[t])}</p>
        </div>
      ))}
      <div className={`${tile} sm:col-span-2`}>
        <p className="text-sm text-muted">Owed to sellers (not revenue)</p>
        <p className="mt-1 text-2xl font-bold tabular-nums text-ink">{formatCents(report.owedToSellersCents)}</p>
        <p className="mt-1 text-xs text-muted">Held for sellers who haven't been paid out yet. A cash sale owes only its deposit.</p>
      </div>
      <div className={`${tile} sm:col-span-2`}>
        <p className="text-sm text-muted">Deposits held right now (not revenue)</p>
        <p className="mt-1 text-2xl font-bold tabular-nums text-ink">{formatCents(report.depositsHeldCents)}</p>
        <p className="mt-1 text-xs text-muted">Reservations still running. A deposit becomes revenue only if it is forfeited.</p>
      </div>
    </section>
  )
}

function SeriesTable({ report }: { report: RevenueReport }) {
  return (
    <div className="relative max-h-96 overflow-auto">
      <table className="min-w-full divide-y divide-line text-sm">
        <caption className="sr-only">Revenue per {report.groupBy} by source</caption>
        <thead className="sticky top-0 bg-white">
          <tr><th className="py-2 text-left font-semibold">Period</th>{LEDGER_TYPES.map((t) => <th key={t} className="py-2 text-right font-semibold">{LEDGER_LABELS[t]}</th>)}<th className="py-2 text-right font-semibold">Net</th></tr>
        </thead>
        <tbody className="divide-y divide-line">
          {report.series.map((b) => (
            <tr key={b.bucket}><td className="py-1.5">{b.bucket}</td>{LEDGER_TYPES.map((t) => <td key={t} className="py-1.5 text-right tabular-nums">{formatCents(b.byType[t])}</td>)}<td className="py-1.5 text-right font-medium tabular-nums">{formatCents(b.netCents)}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
