import client from './client'

export type Role = 'STUDENT' | 'ADMIN'

export interface User {
  id: number
  email: string
  role: Role
}

export const getMe = () => client.get<User>('/api/users/me')
