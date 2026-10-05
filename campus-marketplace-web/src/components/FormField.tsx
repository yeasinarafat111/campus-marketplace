import type { InputHTMLAttributes } from 'react'

interface FormFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  error?: string
}

export default function FormField({ label, error, id, ...props }: FormFieldProps) {
  const inputId = id ?? props.name
  return (
    <div>
      <label htmlFor={inputId} className="block text-sm font-semibold text-navy">
        {label}
      </label>
      <input id={inputId} aria-invalid={error ? true : undefined} className="input mt-1" {...props} />
      {error && <p className="mt-1 text-sm text-danger">{error}</p>}
    </div>
  )
}
