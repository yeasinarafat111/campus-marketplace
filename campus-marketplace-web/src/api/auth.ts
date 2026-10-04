import client from './client'

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

export interface MessageResponse {
  message: string
}

export const signup = (body: { fullName: string; email: string; password: string }) =>
  client.post<{ id: number; fullName: string; email: string; message: string }>('/api/auth/signup', body)

export const verifyOtp = (body: { email: string; code: string }) =>
  client.post<{ email: string; enabled: boolean; message: string }>('/api/auth/verify-otp', body)

export const resendOtp = (body: { email: string }) =>
  client.post<MessageResponse>('/api/auth/resend-otp', body)

export const login = (body: { email: string; password: string }) =>
  client.post<AuthResponse>('/api/auth/login', body)

// NOTE: the backend does not expose these two endpoints yet (only OtpPurpose.RESET_PASSWORD
// exists). Paths and payloads below are assumed; adjust when the backend adds them.
export const forgotPassword = (body: { email: string }) =>
  client.post<MessageResponse>('/api/auth/forgot-password', body)

export const resetPassword = (body: { email: string; code: string; newPassword: string }) =>
  client.post<MessageResponse>('/api/auth/reset-password', body)
