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
  return (
    <nav aria-label="Pagination" className="mt-6 flex items-center justify-between border-t border-rule pt-4">
      <button className="btn btn-outline btn-sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        Previous
      </button>
      <span className="text-sm text-muted">
        Page {page + 1} of {totalPages}
      </span>
      <button className="btn btn-outline btn-sm" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>
        Next
      </button>
    </nav>
  )
}
