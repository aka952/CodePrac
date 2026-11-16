import { lazy, StrictMode, Suspense } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Link, NavLink, Route, Routes } from 'react-router-dom'
import ExerciseList from './pages/ExerciseList'
import RepoList from './pages/RepoList'
import './styles.css'

const ExercisePage = lazy(() => import('./pages/ExercisePage'))
const RepoPage = lazy(() => import('./pages/RepoPage'))

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
