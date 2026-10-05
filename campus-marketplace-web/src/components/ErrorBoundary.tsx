import { Component, type ErrorInfo, type ReactNode } from 'react'

/** Last-resort fallback so a rendering bug shows a message instead of a blank page. */
export default class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled UI error', error, info.componentStack)
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <div className="mx-auto max-w-xl px-4 py-16">
        <div className="border-t-4 border-danger pt-6">
          <h1 className="text-4xl">Something went wrong</h1>
          <p className="mt-3 text-lg text-muted">The page hit an unexpected error. Reloading usually fixes it.</p>
          <button className="btn btn-primary mt-6" onClick={() => window.location.reload()}>
            Reload the page
          </button>
        </div>
      </div>
    )
  }
}
