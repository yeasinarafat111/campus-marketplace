import { Info } from 'lucide-react'

/** Payments run against Stripe's test mode for the showcase; say so wherever someone is about to pay. */
export default function DemoNotice({ className = '' }: { className?: string }) {
  return (
    <p className={`flex items-start gap-2 rounded-lg bg-navy-soft px-3 py-2 text-xs text-navy ${className}`}>
      <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden />
      <span>Demo mode: payments are simulated. Use test card 4242 4242 4242 4242.</span>
    </p>
  )
}
