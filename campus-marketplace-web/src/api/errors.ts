import { isAxiosError } from 'axios'

export interface ApiError {
  status?: number
  message: string
  fieldErrors: Record<string, string>
}

/** Normalises the backend's ErrorResponse (message + optional fieldErrors) for display. */
export function parseApiError(err: unknown): ApiError {
  if (isAxiosError(err)) {
    if (!err.response) return { message: 'Cannot reach the server. Please try again.', fieldErrors: {} }
    const data = err.response.data as { message?: string; fieldErrors?: Record<string, string> } | undefined
    return {
      status: err.response.status,
      message: data?.message ?? 'Something went wrong. Please try again.',
      fieldErrors: data?.fieldErrors ?? {},
    }
  }
  return { message: 'Something went wrong. Please try again.', fieldErrors: {} }
}
