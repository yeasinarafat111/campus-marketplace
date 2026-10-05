import { Link } from 'react-router-dom'
import StatePanel from '../components/StatePanel'
import { useDocumentTitle } from '../hooks/useDocumentTitle'

export default function NotFoundPage({ what = 'page' }: { what?: string }) {
  useDocumentTitle('Not found')
  return (
    <StatePanel
      title={`We couldn't find that ${what}`}
      actions={
        <Link to="/listings" className="btn btn-primary">
          Back to the board
        </Link>
      }
    >
      It may have been removed, sold and taken down, or the link is mistyped.
    </StatePanel>
  )
}
