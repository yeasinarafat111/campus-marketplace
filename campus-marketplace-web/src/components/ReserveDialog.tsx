import { useState } from 'react'
import type { FeeQuote } from '../api/payments'
import { formatCents } from '../lib'
import CheckoutButton from './CheckoutButton'
import DemoNotice from './DemoNotice'
import Button from './ui/Button'
import Dialog from './ui/Dialog'

/**
 * The terms of a reservation (decisions D3, D4, D5), shown before the buyer is sent to Stripe. They must
 * tick "I understand" first. The amounts come from the server's quote.
 */
export default function ReserveDialog({
  open,
  onOpenChange,
  listingId,
  quote,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  listingId: number
  quote: FeeQuote
}) {
  const [understood, setUnderstood] = useState(false)
  const deposit = formatCents(quote.reserveCents)
  const balance = formatCents(quote.purchaseCents)

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) setUnderstood(false)
        onOpenChange(next)
      }}
      title="Reserve this item"
      description={`You'll pay a ${deposit} deposit now to hold it.`}
    >
      <ul className="list-disc space-y-2 pl-5 text-sm text-ink">
        <li>The item is held for you for <strong>{quote.reservationHours} hours</strong>.</li>
        <li>
          The deposit counts toward the price. When you buy, you pay the balance of <strong className="tabular-nums">{balance}</strong>.
        </li>
        <li>
          The deposit is <strong>refunded only if the seller cancels</strong>. If you cancel, or don't buy within {quote.reservationHours} hours, the deposit is kept.
        </li>
      </ul>
      <label className="mt-5 flex cursor-pointer items-start gap-3 rounded-lg bg-surface p-3 text-sm">
        <input
          type="checkbox"
          checked={understood}
          onChange={(e) => setUnderstood(e.target.checked)}
          className="mt-0.5 size-4 accent-navy"
        />
        <span>I understand these terms.</span>
      </label>
      <DemoNotice className="mt-4" />
      <div className="mt-5 flex justify-end gap-2">
        <Button variant="secondary" onClick={() => onOpenChange(false)}>
          Cancel
        </Button>
        <CheckoutButton listingId={listingId} purpose="RESERVE_ITEM" disabled={!understood}>
          Continue to payment
        </CheckoutButton>
      </div>
    </Dialog>
  )
}
