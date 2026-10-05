import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { parseApiError } from '../api/errors'
import { rateOrder, type Order } from '../api/orders'
import Alert from './Alert'
import { StarInput } from './StarRating'
import Button from './ui/Button'
import Dialog from './ui/Dialog'
import { Textarea } from './ui/Input'

/** Buyer: rate the seller for a completed order. Opens a small dialog with stars and an optional comment. */
export default function RateSellerButton({ order }: { order: Order }) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [stars, setStars] = useState(0)
  const [comment, setComment] = useState('')
  const [error, setError] = useState('')

  const send = useMutation({
    mutationFn: () => rateOrder(order.id, stars, comment),
    onSuccess: () => {
      toast.success('Thanks for rating')
      setOpen(false)
      queryClient.invalidateQueries({ queryKey: ['orders'] })
      queryClient.invalidateQueries({ queryKey: ['seller-profile'] })
    },
    onError: (e) => setError(parseApiError(e).message),
  })

  return (
    <>
      <Button size="sm" variant="secondary" onClick={() => { setError(''); setOpen(true) }}>Rate the seller</Button>
      <Dialog open={open} onOpenChange={setOpen} title={`Rate ${order.counterparty.fullName}`} description="How was the sale? Your rating shows on their profile.">
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault()
            if (stars === 0) return setError('Choose 1 to 5 stars.')
            setError('')
            send.mutate()
          }}
        >
          {error && <Alert kind="error">{error}</Alert>}
          <StarInput value={stars} onChange={setStars} />
          <div>
            <label htmlFor="rating-comment" className="mb-1.5 block text-sm font-medium text-ink">Comment <span className="font-normal text-muted">(optional)</span></label>
            <Textarea id="rating-comment" rows={3} maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} />
          </div>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" loading={send.isPending}>Send rating</Button>
          </div>
        </form>
      </Dialog>
    </>
  )
}
