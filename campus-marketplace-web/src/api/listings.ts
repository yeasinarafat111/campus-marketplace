import client from './client'

export const CATEGORIES = ['BOOKS', 'ELECTRONICS', 'FURNITURE', 'CLOTHING', 'STATIONERY', 'SPORTS', 'OTHER'] as const
export type Category = (typeof CATEGORIES)[number]
export type ListingStatus = 'ACTIVE' | 'SOLD'

export const MEETUP_SPOTS = ['MAIN_GATE', 'LIBRARY', 'CAFETERIA', 'PERMANENT_CAMPUS', 'OTHER'] as const
export type MeetupSpot = (typeof MEETUP_SPOTS)[number]
export const MEETUP_LABELS: Record<MeetupSpot, string> = {
  MAIN_GATE: 'Main gate',
  LIBRARY: 'Library',
  CAFETERIA: 'Cafeteria',
  PERMANENT_CAMPUS: 'Permanent campus',
  OTHER: 'Other (agree in chat)',
}

export interface Listing {
  id: number
  title: string
  description: string | null
  price: number
  category: Category
  imageUrl: string | null
  meetupSpot: MeetupSpot | null
  status: ListingStatus
  createdAt: string
  owner: { id: number; fullName: string; memberSince: string }
  featured: boolean
  featuredUntil: string | null
  reserved: boolean
  /** The caller holds the reservation. Always false for anonymous viewers and in search results. */
  reservedByMe: boolean
  /** When the reservation lapses. Only for the two sides of a reservation. */
  reservationExpiresAt: string | null
  /** The other side's name and phone: the buyer to the seller, the seller to the buyer. Parties only. */
  counterparty: Contact | null
}

export interface Contact {
  id: number
  fullName: string
  phone: string | null
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

export interface ListingInput {
  title: string
  description: string
  price: string
  category: Category
  imageUrl?: string
  /** Empty string means "no preference". */
  meetupSpot: MeetupSpot | ''
}

export interface SearchParams {
  keyword?: string
  category?: string
  minPrice?: string
  maxPrice?: string
  ownerId?: number
  /** True: only currently featured listings. */
  featured?: boolean
  /** Omit for the default order: featured listings first, then newest. */
  sort?: 'createdAt' | 'price' | 'title'
  direction?: 'asc' | 'desc'
  page?: number
  size?: number
}

export const searchListings = (params: SearchParams) =>
  client.get<Page<Listing>>('/api/listings', { params }).then((r) => r.data)

/** The front page's Featured row (cached on the server for a minute). */
export const getFeatured = () => client.get<Listing[]>('/api/listings/featured').then((r) => r.data)

/** Active listings per category, for the chips (cached on the server for five minutes). */
export const getCategoryCounts = () =>
  client.get<Record<Category, number>>('/api/listings/category-counts').then((r) => r.data)

export const getListing = (id: string | number) => client.get<Listing>(`/api/listings/${id}`).then((r) => r.data)

export const myListings = (params: { status?: ListingStatus; reserved?: boolean; page?: number; size?: number }) =>
  client.get<Page<Listing>>('/api/listings/mine', { params }).then((r) => r.data)

/** Multipart so an optional image file can be uploaded (the backend sends it to Cloudinary). */
export function createListing(input: ListingInput, image: File | null) {
  const form = new FormData()
  form.append('title', input.title)
  if (input.description) form.append('description', input.description)
  form.append('price', input.price)
  form.append('category', input.category)
  if (input.meetupSpot) form.append('meetupSpot', input.meetupSpot)
  if (image) form.append('image', image)
  // Overrides the client's JSON default; the browser then adds the multipart boundary.
  return client
    .post<Listing>('/api/listings', form, { headers: { 'Content-Type': 'multipart/form-data' } })
    .then((r) => r.data)
}

// PUT is a full replacement and JSON-only on the backend, so the image can only be changed via imageUrl.
export const updateListing = (id: number, input: ListingInput) =>
  client
    .put<Listing>(`/api/listings/${id}`, { ...input, price: Number(input.price), meetupSpot: input.meetupSpot || null })
    .then((r) => r.data)

export const markSold = (id: number) => client.patch<Listing>(`/api/listings/${id}/sold`).then((r) => r.data)

/** Seller: refunds the buyer's deposit and frees the item. */
export const cancelReservation = (id: number) =>
  client.post<Listing>(`/api/listings/${id}/reservation/cancel`).then((r) => r.data)

/** Buyer: gives the item up. The deposit is kept. */
export const releaseReservation = (id: number) =>
  client.post<Listing>(`/api/listings/${id}/reservation/release`).then((r) => r.data)

export const deleteListing = (id: number) => client.delete(`/api/listings/${id}`)
