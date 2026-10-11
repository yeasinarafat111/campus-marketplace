export default function Footer() {
  return (
    <footer className="mt-16 border-t border-line bg-surface">
      <div className="mx-auto flex max-w-7xl flex-col gap-1 px-4 py-6 text-sm text-muted sm:flex-row sm:justify-between">
        <p>Campus Marketplace, for Southeast University students.</p>
        <p>Meet on campus, inspect before you pay.</p>
      </div>
      <p className="mx-auto max-w-7xl px-4 pb-6 text-xs text-muted">
        When you use Ask AI, the listing's photo, title, description, category, condition and price are processed by
        Google Gemini. No names, emails or phone numbers are sent.
      </p>
    </footer>
  )
}
