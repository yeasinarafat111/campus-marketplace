import { useMutation } from '@tanstack/react-query'
import { Flag } from 'lucide-react'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { REPORT_REASONS, REPORT_REASON_LABELS, type ReportReason } from '../api/admin'
import { parseApiError } from '../api/errors'
import { reportListing } from '../api/listings'
import { useAuth } from '../hooks/useAuth'
import Alert from './Alert'
import Button from './ui/Button'
import Dialog from './ui/Dialog'
import { Textarea } from './ui/Input'

/** "Report this listing": a reason (required) and optional details. One report per person per listing. */
export default function ReportListingButton({ listingId }: { listingId: number }) {
  const { isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState<ReportReason | ''>('')
  const [details, setDetails] = useState('')
  const [error, setError] = useState('')

  const send = useMutation({
    mutationFn: () => reportListing(listingId, reason, details),
    onSuccess: () => {
      toast.success('Thanks, we will take a look.')
      setOpen(false)
      setReason('')
      setDetails('')
    },
    onError: (e) => setError(parseApiError(e).message),
  })

  return (
    <>
      <button
        type="button"
        onClick={() => {
          if (!isAuthenticated) return navigate('/login', { state: { from: location } })
          setError('')
          setOpen(true)
        }}
        className="inline-flex min-h-11 items-center gap-1.5 text-sm font-medium text-muted underline-offset-2 hover:text-danger hover:underline"
      >
        <Flag className="size-4" aria-hidden />
        Report this listing
      </button>
      <Dialog open={open} onOpenChange={setOpen} title="Report this listing" description="Tell us what's wrong. An admin will review it.">
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault()
            setError('')
            if (!reason) return setError('Choose a reason.')
            send.mutate()
          }}
        >
          {error && <Alert kind="error">{error}</Alert>}
          <fieldset className="space-y-1">
            <legend className="mb-1 text-sm font-medium text-ink">Reason</legend>
            {REPORT_REASONS.map((r) => (
              <label key={r} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-lg px-2 hover:bg-surface">
                <input type="radio" name="report-reason" value={r} checked={reason === r} onChange={() => setReason(r)} className="size-4 accent-navy" />
                <span className="text-sm text-ink">{REPORT_REASON_LABELS[r]}</span>
              </label>
            ))}
          </fieldset>
          <div>
            <label htmlFor="report-details" className="mb-1.5 block text-sm font-medium text-ink">Details <span className="font-normal text-muted">(optional)</span></label>
            <Textarea id="report-details" rows={3} maxLength={500} value={details} onChange={(e) => setDetails(e.target.value)} />
          </div>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" loading={send.isPending}>Send report</Button>
          </div>
        </form>
      </Dialog>
    </>
  )
}
