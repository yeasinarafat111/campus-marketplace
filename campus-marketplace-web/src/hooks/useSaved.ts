import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { parseApiError } from '../api/errors'
import { getSavedIds, saveListing, unsaveListing } from '../api/listings'
import { useAuth } from './useAuth'

const IDS_KEY = ['saved-ids'] as const

/** Whether each listing is saved by the logged-in user, with an optimistic toggle. */
export function useSaved() {
  const { isAuthenticated, user } = useAuth()
  const queryClient = useQueryClient()
  const key = [...IDS_KEY, user?.id ?? null]

  const ids = useQuery({ queryKey: key, queryFn: getSavedIds, enabled: isAuthenticated, staleTime: 5 * 60_000 })
  const saved = new Set(ids.data ?? [])

  const toggle = useMutation({
    mutationFn: ({ id, save }: { id: number; save: boolean }) => (save ? saveListing(id) : unsaveListing(id)),
    onMutate: async ({ id, save }) => {
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData<number[]>(key)
      queryClient.setQueryData<number[]>(key, (old = []) => (save ? [...old.filter((x) => x !== id), id] : old.filter((x) => x !== id)))
      return { previous }
    },
    onError: (err, _vars, context) => {
      queryClient.setQueryData(key, context?.previous)
      toast.error(parseApiError(err).message)
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['saved'] })
    },
  })

  return {
    isAuthenticated,
    isSaved: (id: number) => saved.has(id),
    toggle: (id: number) => toggle.mutate({ id, save: !saved.has(id) }),
  }
}
