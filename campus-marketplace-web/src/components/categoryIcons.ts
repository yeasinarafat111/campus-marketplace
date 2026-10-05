import { Armchair, BookOpen, Dumbbell, Laptop, Package, PencilLine, Shirt, type LucideIcon } from 'lucide-react'
import type { Category } from '../api/listings'

export const CATEGORY_ICONS: Record<Category, LucideIcon> = {
  BOOKS: BookOpen,
  ELECTRONICS: Laptop,
  FURNITURE: Armchair,
  CLOTHING: Shirt,
  STATIONERY: PencilLine,
  SPORTS: Dumbbell,
  OTHER: Package,
}
