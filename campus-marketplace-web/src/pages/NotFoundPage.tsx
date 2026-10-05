import { SearchX } from 'lucide-react'
import { Link } from 'react-router-dom'
import StatePanel from '../components/StatePanel'
import { buttonClasses } from '../components/ui/buttonClasses'
import { useDocumentTitle } from '../hooks/useDocumentTitle'

export default function NotFoundPage({ what = 'page' }: { what?: string }) {
  useDocumentTitle('Not found')
  return (
    <StatePanel
      icon={SearchX}
      title={`We couldn't find that ${what}`}
      actions={
        <Link to="/listings" className={buttonClasses()}>
          Back to browsing
        </Link>
      }
    >
      It may have been removed, sold and taken down, or the link is mistyped.
    </StatePanel>
  )
}
