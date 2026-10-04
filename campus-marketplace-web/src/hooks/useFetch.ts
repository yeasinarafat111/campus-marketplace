import { useCallback, useEffect, useState } from 'react'
import { parseApiError } from '../api/errors'

/** Runs `fetcher` on mount and whenever `deps` change; `reload` re-runs it. Ignores stale responses. */
export function useFetch<T>(fetcher: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    fetcher()
      .then((d) => !cancelled && setData(d))
      .catch((e) => !cancelled && setError(parseApiError(e).message))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  return { data, error, loading, reload }
}
