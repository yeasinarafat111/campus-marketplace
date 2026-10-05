export default function Alert({ kind, children }: { kind: 'error' | 'success'; children: React.ReactNode }) {
  const styles = kind === 'error' ? 'border-danger text-danger' : 'border-sage text-sage-deep'
  return (
    <div role={kind === 'error' ? 'alert' : 'status'} className={`border-l-4 bg-white px-3 py-2 text-sm ${styles}`}>
      {children}
    </div>
  )
}
