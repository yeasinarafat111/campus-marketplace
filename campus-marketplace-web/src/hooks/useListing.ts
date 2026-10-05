import { useQuery } from '@tanstack/react-query'
import { getListing } from '../api/listings'
import { useAuth } from './useAuth'

/**
 * One listing as the current viewer sees it. The response depends on who is asking (reservedByMe, the
 * other side's phone), so the cache key includes the viewer, and nothing is fetched until the session has
 * been restored: otherwise an anonymous response fetched during page load would be shown to a logged-in user.
 * Invalidate with the prefix ['listing', String(id)].
 */
export function useListing(id: string | number | undefined, options: { refetchInterval?: (data: Awaited<ReturnType<typeof getListing>> | undefined) => number | false; staleTime?: number } = {}) {
  const { user, isLoading } = useAuth()
  return useQuery({
    queryKey: ['listing', String(id), user?.id ?? 'anonymous'],
    queryFn: () => getListing(id!),
    enabled: id !== undefined && !isLoading,
    staleTime: options.staleTime,
    refetchInterval: (q) => options.refetchInterval?.(q.state.data) ?? false,
  })
}
