import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Difficulty, type ExerciseSummary, TYPE_LABEL } from '../api'

const DIFFICULTIES: Difficulty[] = ['EASY', 'MEDIUM', 'HARD']

export default function ExerciseList() {
  const [items, setItems] = useState<ExerciseSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [language, setLanguage] = useState<string | null>(null)
  const [difficulty, setDifficulty] = useState<Difficulty | null>(null)
  const [hideSolved, setHideSolved] = useState(false)

  useEffect(() => {
    api.exercises().then(setItems).catch(e => setError(e.message))
  }, [])

  const languages = useMemo(() => [...new Set(items?.map(i => i.language))].sort(), [items])
  const visible = useMemo(() => (items ?? []).filter(i =>
    (!language || i.language === language) &&
    (!difficulty || i.difficulty === difficulty) &&
    (!hideSolved || i.status !== 'SOLVED')), [items, language, difficulty, hideSolved])

  if (error) return <p className="error">Could not load exercises: {error}</p>
  if (!items) return <p className="muted">Loading exercises…</p>

  const solved = items.filter(i => i.status === 'SOLVED').length

  return (
    <section>
      <div className="list-head">
        <div>
          <h1>Exercises</h1>
          <p className="muted">Read the code, commit to an answer, then break it in the sandbox.</p>
        </div>
        <div className="progress" title={`${solved} of ${items.length} solved`}>
          <div className="progress-bar"><div style={{ width: `${(solved / items.length) * 100}%` }} /></div>
          <span>{solved} / {items.length} solved</span>
        </div>
      </div>

      <div className="filters">
        <Chip active={!language} onClick={() => setLanguage(null)}>All languages</Chip>
        {languages.map(l => <Chip key={l} active={language === l} onClick={() => setLanguage(l)}>{l}</Chip>)}
        <span className="sep" />
        <Chip active={!difficulty} onClick={() => setDifficulty(null)}>Any level</Chip>
        {DIFFICULTIES.map(d => <Chip key={d} active={difficulty === d} onClick={() => setDifficulty(d)}>{d.toLowerCase()}</Chip>)}
        <span className="sep" />
        <Chip active={hideSolved} onClick={() => setHideSolved(h => !h)}>Hide solved</Chip>
      </div>

      <ul className="exercise-grid">
        {visible.map(e => (
          <li key={e.slug}>
            <Link to={`/exercise/${e.slug}`} className={`card status-${e.status.toLowerCase()}`}>
              <div className="card-top">
                <span className={`lang lang-${e.language}`}>{e.language}</span>
                <span className={`diff diff-${e.difficulty.toLowerCase()}`}>{e.difficulty.toLowerCase()}</span>
                <span className={`pts${e.points ? ' earned' : ''}`}>{e.points ? `${e.points} / ${e.maxPoints}` : e.maxPoints} pts</span>
                <StatusDot status={e.status} />
              </div>
              <h3>{e.title}</h3>
              <p className="type">{TYPE_LABEL[e.type]}</p>
              <div className="tags">{e.tags.map(t => <span key={t}>#{t}</span>)}</div>
            </Link>
          </li>
        ))}
        {visible.length === 0 && <li className="muted">Nothing matches these filters.</li>}
      </ul>
    </section>
  )
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return <button className={`chip${active ? ' active' : ''}`} onClick={onClick}>{children}</button>
}

function StatusDot({ status }: { status: ExerciseSummary['status'] }) {
  const label = { NEW: 'Not started', ATTEMPTED: 'Attempted', SOLVED: 'Solved' }[status]
  return <span className={`dot dot-${status.toLowerCase()}`} title={label} aria-label={label} />
}
