import { lazy, StrictMode, Suspense, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Link, NavLink, Route, Routes } from 'react-router-dom'
import { api, type Me, POINTS_EVENT, type PointsSummary, SIGNED_OUT_EVENT, setDevHeaderMode } from './api'
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

function UserMenu({ me, onSignedOut }: { me: Me; onSignedOut: () => void }) {
  if (!me.user) return <span className="user-menu muted small" title="AUTH_DEV_HEADER is on">dev mode</span>
  const signOut = async () => {
    await api.logout().catch(() => {})
    onSignedOut()
  }
  return (
    <span className="user-menu">
      {me.user.picture && <img src={me.user.picture} alt="" referrerPolicy="no-referrer" />}
      <span className="small" title={me.user.email ?? undefined}>{me.user.name ?? me.user.email}</span>
      <button className="link small" onClick={signOut}>Sign out</button>
    </span>
  )
}

function SignIn({ me }: { me: Me }) {
  const failed = new URLSearchParams(window.location.search).get('signin') === 'failed'
  return (
    <div className="signin">
      <h1><span className="brand-mark">&lt;?&gt;</span> Read the Code</h1>
      <p className="muted">Practise reading and reviewing code: predict what it does, find what's wrong, then fix it.</p>
      {me.googleConfigured ? (
        <a className="google-button" href="/api/auth/oauth2/authorization/google">
          <svg viewBox="0 0 48 48" width="18" height="18" aria-hidden="true">
            <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
            <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
            <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
            <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
          </svg>
          Sign in with Google
        </a>
      ) : (
        <p className="error">Sign-in is not configured on this server: set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET on the backend.</p>
      )}
      {failed && <p className="error">Sign-in didn't complete. Please try again.</p>}
    </div>
  )
}

function AuthGate() {
  const [me, setMe] = useState<Me | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () => api.me().then(m => { setDevHeaderMode(m.devHeader && !m.user); setMe(m) }).catch(e => setError(e.message))

  useEffect(() => {
    load()
    const signedOut = () => setMe(m => (m && !m.devHeader ? { ...m, user: null } : m))
    window.addEventListener(SIGNED_OUT_EVENT, signedOut)
    return () => window.removeEventListener(SIGNED_OUT_EVENT, signedOut)
  }, [])

  if (error) return <p className="error signin">Could not reach the server: {error}</p>
  if (!me) return null
  if (!me.user && !me.devHeader) return <SignIn me={me} />
  return <App me={me} onSignedOut={() => { setMe(null); load() }} />
}

function App({ me, onSignedOut }: { me: Me; onSignedOut: () => void }) {
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
        <UserMenu me={me} onSignedOut={onSignedOut} />
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
    <AuthGate />
  </StrictMode>,
)
