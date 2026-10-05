import type { ComponentProps, ReactNode } from 'react'
import Field from './ui/Field'
import { Input } from './ui/Input'

interface FormFieldProps extends ComponentProps<'input'> {
  label: string
  error?: string
  hint?: ReactNode
}

/** A labelled text input with an error line: the common case of Field + Input. */
export default function FormField({ label, error, hint, id, ...props }: FormFieldProps) {
  return (
    <Field label={label} error={error} hint={hint}>
      <Input id={id ?? props.name} {...props} />
    </Field>
  )
}
