import { useQuery } from '@tanstack/react-query'
import { Heart } from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'
import { getSaved } from '../api/listings'
import { parseApiError } from '../api/errors'
import Alert from '../components/Alert'
import ListingCard from '../components/ListingCard'
import Pagination from '../components/Pagination'
import { buttonClasses } from '../components/ui/buttonClasses'
import EmptyState from '../components/ui/EmptyState'
import { ListingCardSkeleton } from '../components/ui/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useDocumentTitle } from '../hooks/useDocumentTitle'

export default function SavedPage() {
  useDocumentTitle('Saved')
  const { user } = useAuth()
  const [params, setParams] = useSearchParams()
  const page = Number(params.get('page') ?? 0) || 0

  const { data, error, isPending } = useQuery({
    queryKey: ['saved', user?.id, page],
    queryFn: () => getSaved({ page, size: 20 }),
  })

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold tracking-tight text-navy">Saved</h1>
      {error && <Alert kind="error">{parseApiError(error).message}</Alert>}
      {isPending && !error && (
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {Array.from({ length: 5 }, (_, i) => <ListingCardSkeleton key={i} />)}
        </div>
      )}
      {data && data.content.length === 0 && (
        <EmptyState icon={Heart} title="Nothing saved yet"
          action={<Link to="/listings" className={buttonClasses()}>Browse listings</Link>}>
          Tap the heart on a listing to keep it here.
        </EmptyState>
      )}
      {data && data.content.length > 0 && (
        <>
          <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            {data.content.map((l) => <ListingCard key={l.id} listing={l} />)}
          </div>
          <Pagination page={data.page} totalPages={data.totalPages}
            onChange={(p) => { setParams(p ? { page: String(p) } : {}); window.scrollTo({ top: 0 }) }} />
        </>
      )}
    </div>
  )
}
