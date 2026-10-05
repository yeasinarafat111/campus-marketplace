import { TriangleAlert } from 'lucide-react'
import { Component, type ErrorInfo, type ReactNode } from 'react'
import StatePanel from './StatePanel'
import Button from './ui/Button'

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
      <div className="px-4 py-16">
        <StatePanel
          icon={TriangleAlert}
          title="Something went wrong"
          actions={<Button onClick={() => window.location.reload()}>Reload the page</Button>}
        >
          The page hit an unexpected error. Reloading usually fixes it.
        </StatePanel>
      </div>
    )
  }
}
