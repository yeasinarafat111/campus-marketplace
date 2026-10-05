// Access token lives only in module memory (and React state via AuthProvider), so it is
// gone on page reload and not readable by other scripts via storage APIs.
//
// Refresh token tradeoff: a pure SPA cannot set an httpOnly cookie, so the refresh token is
// kept in localStorage. That survives reloads, but any XSS on this origin can read it and
// mint new access tokens. Acceptable for this project; for production, have the backend set
// the refresh token as an httpOnly, Secure, SameSite cookie instead.
const REFRESH_KEY = 'campus.refreshToken'

let accessToken: string | null = null

export const getAccessToken = () => accessToken
export const setAccessToken = (token: string | null) => {
  accessToken = token
}

/** Seconds until the access token expires (negative if it has), read from its payload, or null if there is none. */
export function accessTokenSecondsLeft(): number | null {
  if (!accessToken) return null
  try {
    const payload = JSON.parse(atob(accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return typeof payload.exp === 'number' ? payload.exp - Date.now() / 1000 : null
  } catch {
    return null
  }
}

export const getRefreshToken = () => localStorage.getItem(REFRESH_KEY)
export const setRefreshToken = (token: string | null) => {
  if (token) localStorage.setItem(REFRESH_KEY, token)
  else localStorage.removeItem(REFRESH_KEY)
}
