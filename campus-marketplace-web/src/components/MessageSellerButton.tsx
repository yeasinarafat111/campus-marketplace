import { useMutation } from '@tanstack/react-query'
import { MessageCircle } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { openConversation } from '../api/chat'
import { parseApiError } from '../api/errors'
import type { Listing } from '../api/listings'
import { useAuth } from '../hooks/useAuth'
import Button from './ui/Button'
import { buttonClasses } from './ui/buttonClasses'

/** Starts (or reopens) the chat with the seller about this listing. Not shown to the seller themself. */
export default function MessageSellerButton({ listing, size = 'lg', block = false }: { listing: Listing; size?: 'md' | 'lg'; block?: boolean }) {
  const { user, isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const open = useMutation({
    mutationFn: () => openConversation(listing.id),
    onSuccess: (c) => navigate(`/messages/${c.id}`),
    onError: (e) => toast.error(parseApiError(e).message),
  })
  if (user?.id === listing.owner.id) return null
  const width = block ? 'w-full' : ''
  if (!isAuthenticated) {
    return (
      <Link to="/login" state={{ from: location }} className={buttonClasses({ variant: 'secondary', size, className: width })}>
        <MessageCircle className="size-4" aria-hidden />
        Log in to message
      </Link>
    )
  }
  return (
    <Button variant="secondary" size={size} className={width} loading={open.isPending} onClick={() => open.mutate()}>
      <MessageCircle className="size-4" aria-hidden />
      Message seller
    </Button>
  )
}
