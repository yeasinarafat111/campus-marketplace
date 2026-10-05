import { CircleSlash } from 'lucide-react'
import { Link } from 'react-router-dom'
import DemoNotice from '../components/DemoNotice'
import StatePanel from '../components/StatePanel'
import { buttonClasses } from '../components/ui/buttonClasses'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { recallCheckout } from '../lib'

export default function PaymentCancelPage() {
  useDocumentTitle('Payment cancelled')
  const listingId = recallCheckout()?.listingId
  return (
    <StatePanel
      note={<DemoNotice />}
      icon={CircleSlash}
      title="Payment cancelled"
      actions={
        <Link to={listingId ? `/listings/${listingId}` : '/listings'} className={buttonClasses()}>
          {listingId ? 'Back to the listing' : 'Back to browsing'}
        </Link>
      }
    >
      You weren't charged, and nothing about the listing has changed.
    </StatePanel>
  )
}
