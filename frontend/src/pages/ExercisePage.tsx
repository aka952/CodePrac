import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, type AttemptResult, type ExerciseSummary, type ExerciseView, type RunResponse, type RunStatus, TYPE_LABEL } from '../api'
import CodeEditor from '../components/CodeEditor'

const REVEAL_AFTER = 3

const RUN_LABEL: Record<RunStatus, string> = {
  OK: 'Exited normally',
  RUNTIME_ERROR: 'Runtime error',
  TIMEOUT: 'Timed out',
  MEMORY_LIMIT: 'Killed: memory limit',
  OUTPUT_LIMIT: 'Killed: output limit',
  SANDBOX_ERROR: 'Sandbox error',
}

export default function ExercisePage() {
  const { slug = '' } = useParams()
  const navigate = useNavigate()
  const [ex, setEx] = useState<ExerciseView | null>(null)
  const [all, setAll] = useState<ExerciseSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [answer, setAnswer] = useState('')
  const [line, setLine] = useState<number | null>(null)
  const [result, setResult] = useState<AttemptResult | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [showHint, setShowHint] = useState(false)
  const [code, setCode] = useState('')
  const [run, setRun] = useState<RunResponse | null>(null)
  const [running, setRunning] = useState(false)
  const [runError, setRunError] = useState<string | null>(null)

  useEffect(() => {
    setEx(null); setError(null); setAnswer(''); setLine(null); setResult(null)
    setShowHint(false); setRun(null); setRunError(null)
    api.exercise(slug).then(e => { setEx(e); setCode(e.code) }).catch(e => setError(e.message))
    api.exercises().then(setAll).catch(() => setAll([]))
  }, [slug])

  const next = useMemo(() => {
    const i = all.findIndex(a => a.slug === slug)
    return i >= 0 ? all[i + 1] : undefined
  }, [all, slug])

  if (error) return <p className="error">{error} <Link to="/">Back to exercises</Link></p>
  if (!ex) return <p className="muted">Loading…</p>

  const solution = result?.solution ?? ex.solution
  const unlocked = solution !== null
  const solvedNow = result?.correct || (ex.solved && !result)
  const isSpotBug = ex.type === 'SPOT_BUG'
  const answerLine = isSpotBug && solution ? Number(solution.answer) : null
  const modified = code !== ex.code
  const codeLines = ex.code.split('\n')

  const submit = async (giveUp = false) => {
    const value = isSpotBug ? (line ? String(line) : '') : answer
    if (!giveUp && !value.trim()) return
    setSubmitting(true)
    try {
      setResult(await api.attempt(slug, giveUp ? { giveUp: true } : { answer: value }))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  const execute = async () => {
    if (!unlocked || running) return
    setRunning(true)
    setRunError(null)
    try {
      setRun(await api.run(ex.language, code))
    } catch (e) {
      setRunError((e as Error).message)
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="exercise">
      <nav className="crumbs">
        <Link to="/">← Exercises</Link>
      </nav>

      <div className="exercise-head">
        <h1>{ex.title}</h1>
        <div className="meta">
          <span className={`lang lang-${ex.language}`}>{ex.language}</span>
          <span className={`diff diff-${ex.difficulty.toLowerCase()}`}>{ex.difficulty.toLowerCase()}</span>
          <span className="type">{TYPE_LABEL[ex.type]}</span>
          {ex.tags.map(t => <span key={t} className="tag">#{t}</span>)}
        </div>
      </div>

      <div className="workspace">
        <div className="code-col">
          <div className="code-bar">
            <span>{unlocked ? 'Experiment mode: edit and run (Ctrl/⌘ + Enter)' : isSpotBug ? 'Click the buggy line' : 'Read only until you answer'}</span>
            {unlocked && modified && <button className="link" onClick={() => setCode(ex.code)}>Reset code</button>}
          </div>
          <CodeEditor
            language={ex.language}
            value={unlocked ? code : ex.code}
            readOnly={!unlocked}
            onChange={setCode}
            selectableLines={isSpotBug && !unlocked}
            selectedLine={line}
            answerLine={answerLine}
            onSelectLine={setLine}
            onRun={execute}
          />
          {unlocked && (
            <div className="run-panel">
              <div className="run-actions">
                <button className="primary" onClick={execute} disabled={running}>
                  {running ? 'Running…' : modified ? '▶ Run my version' : '▶ Run original'}
                </button>
                <span className="muted small">Sandboxed: no network, 256 MB, time-limited</span>
              </div>
              {runError && <p className="error">{runError}</p>}
              {run && <RunOutput run={run} />}
            </div>
          )}
        </div>

        <aside className="question-col">
          <div className="panel">
            <h2>Question</h2>
            <p className="question">{ex.question}</p>
            {ex.hint && !unlocked && (
              showHint
                ? <p className="hint">💡 {ex.hint}</p>
                : <button className="link" onClick={() => setShowHint(true)}>Show hint</button>
            )}

            {!unlocked && (
              <form className="answer" onSubmit={e => { e.preventDefault(); submit() }}>
                {ex.type === 'PREDICT_OUTPUT' && (
                  <textarea value={answer} onChange={e => setAnswer(e.target.value)} rows={6} spellCheck={false}
                            placeholder="Type the exact output, one line per line" autoFocus />
                )}
                {ex.type === 'TRACE' && (
                  <input value={answer} onChange={e => setAnswer(e.target.value)} spellCheck={false}
                         placeholder="Value" autoFocus />
                )}
                {isSpotBug && (
                  <p className="picked">
                    {line ? <>Selected <b>line {line}</b>: <code>{codeLines[line - 1]?.trim()}</code></> : 'No line selected yet.'}
                  </p>
                )}
                <div className="answer-actions">
                  <button className="primary" type="submit" disabled={submitting || (isSpotBug ? !line : !answer.trim())}>
                    Submit answer
                  </button>
                  <button type="button" className="link" onClick={() => submit(true)} disabled={submitting}>Give up</button>
                </div>
              </form>
            )}

            {result && !result.correct && !result.solution && (
              <p className="verdict wrong">
                Not quite. Try again: the answer is revealed after {REVEAL_AFTER} attempts ({result.attempts}/{REVEAL_AFTER}).
              </p>
            )}

            {solution && (
              <div className="solution">
                {solvedNow
                  ? <p className="verdict right">✓ Correct{result ? ` in ${result.attempts} attempt${result.attempts > 1 ? 's' : ''}` : ''}!</p>
                  : <p className="verdict revealed">Answer revealed</p>}
                <h3>Expected</h3>
                {isSpotBug
                  ? <p>Line {solution.answer}: <code>{codeLines[Number(solution.answer) - 1]?.trim()}</code></p>
                  : <pre className="out">{solution.answer}</pre>}
                {result && !result.correct && !isSpotBug && answer.trim() && (
                  <>
                    <h3>You said</h3>
                    <pre className="out yours">{answer}</pre>
                  </>
                )}
                <h3>Why</h3>
                <p className="explanation">{solution.explanation}</p>
                <p className="muted small">Now change the code and run it to test your understanding.</p>
                {next && (
                  <button className="primary next" onClick={() => navigate(`/exercise/${next.slug}`)}>
                    Next: {next.title} →
                  </button>
                )}
              </div>
            )}
          </div>
        </aside>
      </div>
    </div>
  )
}

function RunOutput({ run }: { run: RunResponse }) {
  return (
    <div className={`run-output status-${run.status.toLowerCase()}`}>
      <div className="run-meta">
        <span className="run-status">{RUN_LABEL[run.status]}</span>
        <span>exit {run.exitCode}</span>
        <span>{run.durationMs} ms</span>
        {run.cached && <span className="cached" title="Same code ran before; result served from cache">cached</span>}
        {run.truncated && <span className="cached">output truncated</span>}
      </div>
      {run.stdout && <pre className="out">{run.stdout}</pre>}
      {run.stderr && <pre className="out err">{run.stderr}</pre>}
      {!run.stdout && !run.stderr && <p className="muted small">No output.</p>}
    </div>
  )
}
