import { useQuery } from '@tanstack/react-query'
import { getPostingFee, type Listing } from '../api/listings'
import { formatCents } from '../lib'
import CheckoutButton from './CheckoutButton'
import type { ButtonProps } from './ui/Button'

/** Sends the owner to Stripe to pay the posting fee that publishes a listing waiting for it. */
export default function PayToPublishButton({ listing, size, className }: { listing: Listing; size?: ButtonProps['size']; className?: string }) {
  const quote = useQuery({ queryKey: ['posting-fee', listing.price], queryFn: () => getPostingFee(listing.price) })
  return (
    <CheckoutButton listingId={listing.id} purpose="POSTING_FEE" size={size} className={className} disabled={!quote.data}>
      {quote.data?.applies ? `Pay to publish: ${formatCents(quote.data.feeCents)}` : 'Pay to publish'}
    </CheckoutButton>
  )
}
