export default function SubmitButton({ loading, children }: { loading: boolean; children: React.ReactNode }) {
  return (
    <button type="submit" disabled={loading} className="btn btn-primary w-full">
      {loading ? 'Please wait…' : children}
    </button>
  )
}
