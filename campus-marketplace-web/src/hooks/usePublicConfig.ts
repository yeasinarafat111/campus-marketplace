import { useQuery } from '@tanstack/react-query'
import { getPublicConfig } from '../api/assistant'

/** Server feature switches. If the call fails, every optional feature stays hidden. */
export function usePublicConfig() {
  return useQuery({ queryKey: ['public-config'], queryFn: getPublicConfig, staleTime: 5 * 60_000, retry: 1 })
}
