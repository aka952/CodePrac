import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type RepoSummary } from '../api'

export default function RepoList() {
  const [items, setItems] = useState<RepoSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.repos().then(setItems).catch(e => setError(e.message))
  }, [])

  if (error) return <p className="error">Could not load repo exercises: {error}</p>
  if (!items) return <p className="muted">Loading repo exercises…</p>

  const solved = items.filter(i => i.status === 'SOLVED').length

  return (
    <section>
      <div className="list-head">
        <div>
          <h1>Repo reviews</h1>
          <p className="muted">A small project full of problems. Review it like a pull request, then fix it until the hidden tests pass.</p>
        </div>
        <div className="progress" title={`${solved} of ${items.length} fixed`}>
          <div className="progress-bar"><div style={{ width: `${items.length ? (solved / items.length) * 100 : 0}%` }} /></div>
          <span>{solved} / {items.length} fixed</span>
        </div>
      </div>

      <ul className="exercise-grid">
        {items.map(r => (
          <li key={r.slug}>
            <Link to={`/repo/${r.slug}`} className={`card status-${r.status.toLowerCase()}`}>
              <div className="card-top">
                <span className={`lang lang-${r.language}`}>{r.language}</span>
                <span className={`diff diff-${r.difficulty.toLowerCase()}`}>{r.difficulty.toLowerCase()}</span>
                <span className={`pts${r.points ? ' earned' : ''}`}>{r.points ? `${r.points} / ${r.maxPoints}` : r.maxPoints} pts</span>
                <span className={`dot dot-${r.status.toLowerCase()}`} title={r.status.toLowerCase()} />
              </div>
              <h3>{r.title}</h3>
              <p className="type">{r.files} files · {r.issues} planted issues</p>
              <div className="tags">{r.tags.map(t => <span key={t}>#{t}</span>)}</div>
            </Link>
          </li>
        ))}
        {items.length === 0 && <li className="muted">No repo exercises yet.</li>}
      </ul>
    </section>
  )
}
