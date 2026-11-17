import { lazy, StrictMode, Suspense, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Link, NavLink, Route, Routes } from 'react-router-dom'
import { api, POINTS_EVENT, type PointsSummary } from './api'
import ExerciseList from './pages/ExerciseList'
import RepoList from './pages/RepoList'
import './styles.css'

const ExercisePage = lazy(() => import('./pages/ExercisePage'))
const RepoPage = lazy(() => import('./pages/RepoPage'))

function PointsTotal() {
  const [points, setPoints] = useState<PointsSummary | null>(null)

  useEffect(() => {
    const load = () => { api.points().then(setPoints).catch(() => {}) }
    load()
    window.addEventListener(POINTS_EVENT, load)
    return () => window.removeEventListener(POINTS_EVENT, load)
  }, [])

  if (!points) return null
  return (
    <span className="points-total" title={`${points.snippets} from exercises, ${points.repos} from repo fixes`}>
      <b>{points.total}</b> pts
    </span>
  )
}

function App() {
  return (
    <BrowserRouter>
      <header className="topbar">
        <Link to="/" className="brand">
          <span className="brand-mark">&lt;?&gt;</span> Read the Code
        </Link>
        <span className="tagline">predict first, run second</span>
        <nav className="topnav">
          <NavLink to="/" end>Exercises</NavLink>
          <NavLink to="/repos">Repo reviews</NavLink>
        </nav>
        <PointsTotal />
      </header>
      <main>
        <Routes>
          <Route path="/" element={<ExerciseList />} />
          <Route path="/exercise/:slug" element={<Suspense fallback={<p className="muted">Loading editor…</p>}><ExercisePage /></Suspense>} />
          <Route path="/repos" element={<RepoList />} />
          <Route path="/repo/:slug" element={<Suspense fallback={<p className="muted">Loading editor…</p>}><RepoPage /></Suspense>} />
          <Route path="*" element={<p className="muted">Page not found. <Link to="/">Back to exercises</Link></p>} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
