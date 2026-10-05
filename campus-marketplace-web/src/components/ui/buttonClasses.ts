import { cx } from '../../lib'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'accent'
export type ButtonSize = 'sm' | 'md' | 'lg'

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-navy text-white hover:bg-navy-deep',
  secondary: 'border border-line bg-white text-ink hover:bg-surface',
  ghost: 'text-ink hover:bg-surface',
  danger: 'bg-danger text-white hover:bg-red-800',
  // The Feature button is the only place the gold accent is used on an action.
  accent: 'bg-gold text-navy hover:bg-gold-deep',
}

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-sm',
  md: 'h-10 px-4 text-sm',
  lg: 'h-12 px-6 text-base',
}

/** Class names for a button, so links can look like buttons: `<Link className={buttonClasses()}>`. */
export const buttonClasses = ({
  variant = 'primary',
  size = 'md',
  className,
}: { variant?: ButtonVariant; size?: ButtonSize; className?: string } = {}) =>
  cx(
    'inline-flex shrink-0 items-center justify-center gap-2 rounded-lg font-semibold transition-colors duration-150',
    'disabled:cursor-not-allowed disabled:opacity-60',
    VARIANTS[variant],
    SIZES[size],
    className,
  )
