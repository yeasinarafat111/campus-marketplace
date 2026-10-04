export default function Pagination({
  page,
  totalPages,
  onChange,
}: {
  page: number
  totalPages: number
  onChange: (page: number) => void
}) {
  if (totalPages <= 1) return null
  const btn = 'rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm disabled:opacity-40'
  return (
    <div className="mt-6 flex items-center justify-center gap-3">
      <button className={btn} disabled={page <= 0} onClick={() => onChange(page - 1)}>
        Previous
      </button>
      <span className="text-sm text-gray-600">
        Page {page + 1} of {totalPages}
      </span>
      <button className={btn} disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>
        Next
      </button>
    </div>
  )
}
