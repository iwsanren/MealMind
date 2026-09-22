import type { ButtonHTMLAttributes } from 'react'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary: 'border border-accent bg-accent text-white hover:bg-accent/90',
  secondary: 'border border-transparent bg-accent-subtle text-accent hover:bg-accent-subtle/70',
  ghost: 'border border-border bg-transparent text-text-secondary hover:border-accent hover:text-accent',
  danger: 'border border-danger bg-transparent text-danger hover:bg-danger-subtle',
}

export function buttonClassName(variant: ButtonVariant = 'primary', className = ''): string {
  return [
    'inline-flex items-center justify-center rounded px-4 py-2 text-[14px] font-medium transition-colors',
    VARIANT_CLASSES[variant],
    className,
  ].join(' ')
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
}

export function Button({ variant = 'primary', className, ...props }: ButtonProps) {
  return <button className={buttonClassName(variant, className)} {...props} />
}
