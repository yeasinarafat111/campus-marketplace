import { useCallback, useEffect, useState } from 'react'
import { parseApiError } from '../api/errors'

/** Runs `fetcher` on mount and whenever `deps` change; `reload` re-runs it. Ignores stale responses. */
export function useFetch<T>(fetcher: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState('')
  const [errorStatus, setErrorStatus] = useState<number | undefined>()
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    setErrorStatus(undefined)
    fetcher()
      .then((d) => !cancelled && setData(d))
      .catch((e) => {
        if (cancelled) return
        const parsed = parseApiError(e)
        setError(parsed.message)
        setErrorStatus(parsed.status)
      })
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  return { data, error, errorStatus, loading, reload }
}
