export default function Alert({ kind, children }: { kind: 'error' | 'success'; children: React.ReactNode }) {
  const styles = kind === 'error' ? 'bg-red-50 text-red-700 ring-red-200' : 'bg-green-50 text-green-700 ring-green-200'
  return (
    <div role={kind === 'error' ? 'alert' : 'status'} className={`rounded-md p-3 text-sm ring-1 ${styles}`}>
      {children}
    </div>
  )
}
