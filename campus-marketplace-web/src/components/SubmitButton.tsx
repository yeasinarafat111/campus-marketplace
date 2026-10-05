import type { ReactNode } from 'react'
import Button from './ui/Button'

export default function SubmitButton({ loading, children }: { loading: boolean; children: ReactNode }) {
  return (
    <Button type="submit" size="lg" loading={loading} className="w-full">
      {children}
    </Button>
  )
}
