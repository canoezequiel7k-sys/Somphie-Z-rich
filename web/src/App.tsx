import { Link, Route, Routes, useParams } from 'react-router-dom'
import { TablePage } from './features/client/table-access/TablePage'
import { text } from './shared/i18n'

function LandingPage() {
  return (
    <main className="landing">
      <p className="eyebrow">{text('welcomeEyebrow')}</p>
      <h1>{text('welcomeTitle')}</h1>
      <p className="landing__description">{text('welcomeDescription')}</p>
    </main>
  )
}

function TableRoute() {
  const { tableCode } = useParams()

  if (!tableCode) {
    return <NotFoundPage />
  }

  return <TablePage key={tableCode} tableCode={tableCode} />
}

function NotFoundPage() {
  return (
    <main className="landing">
      <p className="eyebrow">{text('notFoundEyebrow')}</p>
      <h1>{text('notFoundTitle')}</h1>
      <Link className="text-link" to="/">
        {text('backHome')}
      </Link>
    </main>
  )
}

export function AppRoutes() {
  return (
    <div className="app-shell">
      <header className="site-header">
        <Link aria-label={text('brand')} className="brand" to="/">
          <span aria-hidden="true" className="brand__mark">
            S
          </span>
          <span>{text('brand')}</span>
        </Link>
      </header>
      <Routes>
        <Route element={<LandingPage />} path="/" />
        <Route element={<TableRoute />} path="/t/:tableCode" />
        <Route element={<NotFoundPage />} path="*" />
      </Routes>
      <footer className="site-footer">{text('footer')}</footer>
    </div>
  )
}
