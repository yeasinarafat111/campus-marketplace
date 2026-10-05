import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Banknote, Clock, MapPin, Phone, Star } from 'lucide-react'
import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { toast } from 'sonner'
import { MEETUP_LABELS, cancelReservation, chooseCash, confirmCashHandover, releaseReservation, type Listing } from '../api/listings'
import { parseApiError } from '../api/errors'
import { getFeeQuote, type FeeQuote } from '../api/payments'
import { useAuth } from '../hooks/useAuth'
import { useNow } from '../hooks/useNow'
import { formatCents, formatRemaining } from '../lib'
import CheckoutButton from './CheckoutButton'
import PayToPublishButton from './PayToPublishButton'
import ReserveDialog from './ReserveDialog'
import Button from './ui/Button'
import { buttonClasses } from './ui/buttonClasses'
import Dialog, { ConfirmDialog } from './ui/Dialog'

/** Decision D7: only paid listings (at least $1.00) have fees, so only they can be featured or reserved. */
const isChargeable = (listing: Listing) => listing.price >= 1

type Size = 'md' | 'lg'

/**
 * The action buttons for a listing, by viewer (see the table in the playbook):
 * - the owner: Feature, and while reserved the buyer's contact and "Cancel reservation";
 * - the reserver: "Reserved by you", Buy now (the balance), Release, and the seller's phone;
 * - anyone else: Reserve, or a disabled "Reserved". Sold listings have no actions.
 * Every amount shown comes from the server's fee quote.
 */
export default function ListingPaymentActions({ listing, size = 'lg', block = false, compact = false }: { listing: Listing; size?: Size; block?: boolean; compact?: boolean }) {
  const { user, isAuthenticated } = useAuth()
  const isOwner = user?.id === listing.owner.id
  const width = block ? 'w-full' : ''
  const wantsQuote =
    isAuthenticated && listing.status === 'ACTIVE' && isChargeable(listing) &&
    (listing.reservedByMe || (isOwner ? !listing.featured : !listing.reserved))
  const quote = useQuery({
    queryKey: ['fees', listing.id, listing.price],
    queryFn: () => getFeeQuote(listing.id),
    enabled: wantsQuote,
  })
  if (listing.status === 'PENDING_PAYMENT') {
    return isOwner ? (
      <div className={`space-y-2 ${width}`}>
        {!compact && <p className="rounded-lg bg-warning-soft p-3 text-sm text-warning">
          This listing isn't published yet. Pay the posting fee to put it on the board. It is deleted 24 hours after you created it if it is still unpaid.
        </p>}
        <PayToPublishButton listing={listing} size={size} className={width} />
      </div>
    ) : null
  }
  if (listing.status !== 'ACTIVE') return null

  if (listing.reservedByMe) return <ReserverActions listing={listing} quote={quote.data} size={size} width={width} compact={compact} />
  if (isOwner) return <OwnerActions listing={listing} quote={quote.data} size={size} width={width} compact={compact} />
  if (listing.reserved) {
    return (
      <Button variant="secondary" size={size} disabled className={width}>
        Reserved
      </Button>
    )
  }
  return <ReserveAction listing={listing} quote={quote.data} size={size} width={width} />
}

function useRefreshListing(listing: Listing) {
  const queryClient = useQueryClient()
  return () => {
    queryClient.invalidateQueries({ queryKey: ['listing', String(listing.id)] })
    queryClient.invalidateQueries({ queryKey: ['listings'] })
    queryClient.invalidateQueries({ queryKey: ['my-listings'] })
    queryClient.invalidateQueries({ queryKey: ['fees', listing.id] })
  }
}

function OwnerActions({ listing, quote, size, width, compact }: { listing: Listing; quote?: FeeQuote; size: Size; width: string; compact: boolean }) {
  const refresh = useRefreshListing(listing)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const now = useNow()
  const [handoverOpen, setHandoverOpen] = useState(false)
  const handover = useMutation({
    mutationFn: () => confirmCashHandover(listing.id),
    onSuccess: () => {
      toast.success('Sale complete', { description: 'Both of you have been emailed.' })
      setHandoverOpen(false)
      refresh()
    },
    onError: (e) => {
      toast.error(parseApiError(e).message)
      setHandoverOpen(false)
      refresh()
    },
  })
  const cancel = useMutation({
    mutationFn: () => cancelReservation(listing.id),
    onSuccess: () => {
      toast.success('Reservation cancelled', { description: "The buyer's deposit is being refunded." })
      setConfirmOpen(false)
      refresh()
    },
    onError: (e) => {
      toast.error(parseApiError(e).message)
      setConfirmOpen(false)
    },
  })

  return (
    <div className={`space-y-3 ${width}`}>
      {listing.reserved && (
        <>
          {!compact && (
            <div className="rounded-lg bg-warning-soft p-3 text-sm text-warning">
              <p className="flex items-center gap-1.5 font-semibold">
                <Clock className="size-4" aria-hidden />
                Reserved{listing.reservationExpiresAt && `: expires in ${formatRemaining(listing.reservationExpiresAt, now)}`}
              </p>
              {listing.counterparty && <ContactLine name={listing.counterparty.fullName} phone={listing.counterparty.phone} role="Buyer" />}
            </div>
          )}
          {listing.cashChosen && (
            <div className="rounded-lg bg-success-soft p-3 text-sm text-success">
              <p className="flex items-center gap-1.5 font-semibold">
                <Banknote className="size-4" aria-hidden />
                Buyer will pay {formatCents(listing.balanceDueCents ?? 0)} in cash
              </p>
              {listing.meetupSpot && <p className="mt-1 flex items-center gap-1.5 text-ink"><MapPin className="size-3.5 text-muted" aria-hidden />Meetup: {MEETUP_LABELS[listing.meetupSpot]}</p>}
              <Button size={size} className={`mt-3 ${width}`} onClick={() => setHandoverOpen(true)}>
                Confirm handover and cash received
              </Button>
            </div>
          )}
          <ConfirmDialog
            open={handoverOpen}
            onOpenChange={(open) => !open && !handover.isPending && setHandoverOpen(false)}
            title="Confirm the handover?"
            description={`Only confirm after you've handed over the item and received ${formatCents(listing.balanceDueCents ?? 0)} in cash. This completes the sale.`}
            confirmLabel="Yes, I got the cash"
            loading={handover.isPending}
            onConfirm={() => handover.mutate()}
          />
          <Button variant="secondary" size={size} className={width} onClick={() => setConfirmOpen(true)}>
            Cancel reservation (refunds deposit)
          </Button>
          <ConfirmDialog
            open={confirmOpen}
            onOpenChange={(open) => !open && !cancel.isPending && setConfirmOpen(false)}
            title="Cancel this reservation?"
            description={`${listing.counterparty?.fullName ?? 'The buyer'} gets their deposit back in full, and the item goes back on sale.`}
            confirmLabel="Cancel reservation"
            danger
            loading={cancel.isPending}
            onConfirm={() => cancel.mutate()}
          />
        </>
      )}
      {!listing.featured && isChargeable(listing) && (
        <div>
          <CheckoutButton listingId={listing.id} purpose="FEATURE_LISTING" variant="accent" size={size} className={width} disabled={!quote}>
            <Star className="size-4" aria-hidden />
            {quote ? `Feature for ${quote.featureDays} days: ${formatCents(quote.featureCents)}` : 'Feature this listing'}
          </CheckoutButton>
          {quote && <FeeNote quote={quote} />}
        </div>
      )}
    </div>
  )
}

function ReserverActions({ listing, quote, size, width, compact }: { listing: Listing; quote?: FeeQuote; size: Size; width: string; compact: boolean }) {
  const refresh = useRefreshListing(listing)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const now = useNow()
  const release = useMutation({
    mutationFn: () => releaseReservation(listing.id),
    onSuccess: () => {
      toast.success('Reservation released', { description: 'The deposit is not refunded.' })
      setConfirmOpen(false)
      refresh()
    },
    onError: (e) => {
      toast.error(parseApiError(e).message)
      setConfirmOpen(false)
    },
  })
  const expired = listing.reservationExpiresAt !== null && new Date(listing.reservationExpiresAt).getTime() <= now
  const [cashOpen, setCashOpen] = useState(false)
  // Exact figures from the server (price minus the deposit actually paid); the quote is only the fallback.
  const balance = listing.balanceDueCents ?? quote?.purchaseCents ?? 0
  const cash = useMutation({
    mutationFn: () => chooseCash(listing.id),
    onSuccess: () => { toast.success("Noted: you'll pay cash at the meetup"); setCashOpen(false); refresh() },
    onError: (e) => { toast.error(parseApiError(e).message); setCashOpen(false) },
  })

  return (
    <div className={`space-y-3 ${width}`}>
      {!compact && (
        <div className="rounded-lg bg-success-soft p-3 text-sm text-success">
          <p className="flex items-center gap-1.5 font-semibold">
            <Clock className="size-4" aria-hidden />
            Reserved by you{listing.reservationExpiresAt && (expired ? ': expired' : `: expires in ${formatRemaining(listing.reservationExpiresAt, now)}`)}
          </p>
          {listing.counterparty && <ContactLine name={listing.counterparty.fullName} phone={listing.counterparty.phone} role="Seller" />}
        </div>
      )}
      {listing.cashChosen && (
        <div className="rounded-lg bg-surface p-3 text-sm">
          <p className="flex items-center gap-1.5 font-semibold text-ink">
            <Banknote className="size-4" aria-hidden />
            You'll pay {formatCents(balance)} in cash at the meetup
          </p>
          <p className="mt-1 text-muted">The seller confirms the sale when you've handed over the cash and taken the item.</p>
        </div>
      )}
      <CheckoutButton listingId={listing.id} purpose="PURCHASE" size={size} variant={listing.cashChosen ? 'secondary' : 'primary'} className={width} disabled={!quote || expired}>
        {quote ? `${listing.cashChosen ? 'Pay online instead' : 'Pay online'}: ${formatCents(quote.purchaseCents)}` : 'Pay online'}
      </CheckoutButton>
      {quote && <p className="text-center text-xs text-muted">Price minus your {formatCents(quote.reserveCents)} deposit.</p>}
      {!listing.cashChosen && (
        <>
          <Button variant="secondary" size={size} className={width} disabled={expired} onClick={() => setCashOpen(true)}>
            <Banknote className="size-4" aria-hidden />
            Pay cash at meetup
          </Button>
          <Dialog open={cashOpen} onOpenChange={setCashOpen} title="Pay cash at the meetup" description="You'll hand over the cash in person; nothing is charged online.">
            <dl className="space-y-2 rounded-lg bg-surface p-3 text-sm">
              <div className="flex justify-between gap-4"><dt className="text-muted">Cash to bring</dt><dd className="font-bold tabular-nums text-ink">{formatCents(balance)}</dd></div>
              <div className="flex justify-between gap-4"><dt className="text-muted">Seller's phone</dt><dd className="font-semibold text-ink">{listing.counterparty?.phone ?? 'not on file'}</dd></div>
              <div className="flex justify-between gap-4"><dt className="text-muted">Meetup spot</dt><dd className="font-semibold text-ink">{listing.meetupSpot ? MEETUP_LABELS[listing.meetupSpot] : 'Agree with the seller'}</dd></div>
            </dl>
            <p className="mt-3 text-sm text-muted">That is the price minus your deposit. The seller confirms the sale once you've paid. Your reservation still expires on time if the handover doesn't happen.</p>
            <div className="mt-4 flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setCashOpen(false)}>Back</Button>
              <Button loading={cash.isPending} onClick={() => cash.mutate()}>I'll pay cash</Button>
            </div>
          </Dialog>
        </>
      )}
      <Button variant="ghost" size={size} className={`${width} text-danger! hover:bg-danger-soft`} onClick={() => setConfirmOpen(true)}>
        Release reservation
      </Button>
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={(open) => !open && !release.isPending && setConfirmOpen(false)}
        title="Release this reservation?"
        description="The item goes back on sale. Your deposit is not refunded."
        confirmLabel="Release"
        danger
        loading={release.isPending}
        onConfirm={() => release.mutate()}
      />
    </div>
  )
}

function ReserveAction({ listing, quote, size, width }: { listing: Listing; quote?: FeeQuote; size: Size; width: string }) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  const [open, setOpen] = useState(false)
  if (!isChargeable(listing)) {
    return listing.price === 0 ? <p className="text-sm text-muted">This item is free, so it can't be reserved.</p> : null
  }
  if (!isAuthenticated) {
    return (
      <Link to="/login" state={{ from: location }} className={buttonClasses({ size, className: width })}>
        Log in to reserve
      </Link>
    )
  }
  return (
    <div className={width}>
      <Button size={size} className={width} disabled={!quote} onClick={() => setOpen(true)}>
        {quote ? `Reserve: ${formatCents(quote.reserveCents)} deposit` : 'Reserve this item'}
      </Button>
      {quote && <FeeNote quote={quote} />}
      {quote && <ReserveDialog open={open} onOpenChange={setOpen} listingId={listing.id} quote={quote} />}
    </div>
  )
}

function ContactLine({ name, phone, role }: { name: string; phone: string | null; role: string }) {
  return (
    <p className="mt-1 flex flex-wrap items-center gap-x-1.5 text-ink">
      <Phone className="size-3.5 text-muted" aria-hidden />
      <span>
        {role}: {name}
        {phone ? (
          <>
            , <a href={`tel:${phone}`} className="font-semibold underline underline-offset-2">{phone}</a>
          </>
        ) : (
          <span className="text-muted">, no phone on file</span>
        )}
      </span>
    </p>
  )
}

/** "(3% of price, min $0.50)": how the fee above was worked out. */
function FeeNote({ quote }: { quote: { rate: number; minimumCents: number } }) {
  const percent = Number((quote.rate * 100).toFixed(2))
  return (
    <p className="mt-1.5 text-center text-xs text-muted">
      ({percent}% of price, min {formatCents(quote.minimumCents)})
    </p>
  )
}
