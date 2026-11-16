import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type IssueOutcome, type RepoIssue, type RepoView, type ReviewResult, type TestRunResult } from '../api'
import CodeEditor from '../components/CodeEditor'

const baseName = (path: string) => path.slice(path.lastIndexOf('/') + 1)

function commonDir(paths: string[]): string {
  if (paths.length === 0) return ''
  let prefix = paths[0].slice(0, paths[0].lastIndexOf('/') + 1)
  for (const p of paths) while (prefix && !p.startsWith(prefix)) prefix = prefix.slice(0, prefix.lastIndexOf('/', prefix.length - 2) + 1)
  return prefix
}

export default function RepoPage() {
  const { slug = '' } = useParams()
  const [repo, setRepo] = useState<RepoView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [active, setActive] = useState('')
  const [code, setCode] = useState<Record<string, string>>({})
  const [focus, setFocus] = useState<{ file: string; line: number } | null>(null)
  const [review, setReview] = useState('')
  const [result, setResult] = useState<ReviewResult | null>(null)
  const [grading, setGrading] = useState(false)
  const [reviewError, setReviewError] = useState<string | null>(null)
  const [tests, setTests] = useState<TestRunResult | null>(null)
  const [testing, setTesting] = useState(false)
  const [testError, setTestError] = useState<string | null>(null)

  useEffect(() => {
    setRepo(null); setError(null); setResult(null); setReview(''); setTests(null); setFocus(null)
    api.repo(slug).then(r => {
      setRepo(r)
      setActive(r.files[0]?.path ?? '')
      setCode(Object.fromEntries(r.files.map(f => [f.path, f.content])))
    }).catch(e => setError(e.message))
  }, [slug])

  const original = useMemo(() => Object.fromEntries((repo?.files ?? []).map(f => [f.path, f.content])), [repo])
  const dir = useMemo(() => commonDir(repo?.files.map(f => f.path) ?? []), [repo])

  if (error) return <p className="error">{error} <Link to="/repos">Back to repo reviews</Link></p>
  if (!repo) return <p className="muted">Loading…</p>

  const issues: RepoIssue[] | null = result?.issues.map(o => o.issue) ?? repo.issues
  const unlocked = issues !== null
  const modified = Object.keys(code).filter(p => code[p] !== original[p])
  const issueCount = (path: string) => issues?.filter(i => i.file === path).length ?? 0

  const open = (file: string, line: number) => {
    setActive(file)
    setFocus({ file, line })
  }

  const cite = (line: number) => {
    setReview(r => `${r}${r && !r.endsWith('\n') ? '\n' : ''}${baseName(active)}:${line} - `)
    setFocus({ file: active, line })
  }

  const submitReview = async () => {
    if (!review.trim() || grading) return
    setGrading(true)
    setReviewError(null)
    try {
      setResult(await api.review(slug, review))
      setFocus(null)
    } catch (e) {
      setReviewError((e as Error).message)
    } finally {
      setGrading(false)
    }
  }

  const skipReview = async () => {
    if (grading) return
    setGrading(true)
    setReviewError(null)
    try {
      setRepo(await api.reveal(slug))
      setFocus(null)
    } catch (e) {
      setReviewError((e as Error).message)
    } finally {
      setGrading(false)
    }
  }

  const runTests = async () => {
    if (testing) return
    setTesting(true)
    setTestError(null)
    try {
      setTests(await api.testRun(slug, Object.fromEntries(modified.map(p => [p, code[p]]))))
    } catch (e) {
      setTestError((e as Error).message)
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="exercise">
      <nav className="crumbs">
        <Link to="/repos">← Repo reviews</Link>
      </nav>

      <div className="exercise-head">
        <h1>{repo.title}</h1>
        <div className="meta">
          <span className={`lang lang-${repo.language}`}>{repo.language}</span>
          <span className={`diff diff-${repo.difficulty.toLowerCase()}`}>{repo.difficulty.toLowerCase()}</span>
          <span className="type">{repo.files.length} files</span>
          {repo.tags.map(t => <span key={t} className="tag">#{t}</span>)}
        </div>
      </div>

      <div className="workspace">
        <div className="code-col">
          <div className="repo-files">
            {dir && <div className="repo-dir">{dir}</div>}
            {repo.files.map(f => (
              <button key={f.path} className={`repo-file${f.path === active ? ' active' : ''}`} onClick={() => setActive(f.path)}>
                {f.path.slice(dir.length)}
                {modified.includes(f.path) && <span className="modified" title="Modified">●</span>}
                {issueCount(f.path) > 0 && <span className="issue-count" title="Reference issues in this file">{issueCount(f.path)}</span>}
              </button>
            ))}
          </div>
          <div className="code-bar">
            <span>{unlocked ? 'Fix mode: edit any file, then run the hidden tests' : 'Read only. Click a line to cite it in your review'}</span>
            {unlocked && modified.includes(active) && (
              <button className="link" onClick={() => setCode(c => ({ ...c, [active]: original[active] }))}>Reset file</button>
            )}
          </div>
          <CodeEditor
            path={active}
            language={repo.language}
            value={code[active] ?? ''}
            readOnly={!unlocked}
            onChange={v => setCode(c => ({ ...c, [active]: v }))}
            selectableLines={!unlocked}
            selectedLine={!unlocked && focus?.file === active ? focus.line : null}
            answerLine={unlocked && focus?.file === active ? focus.line : null}
            onSelectLine={cite}
            onRun={runTests}
          />
          {unlocked && (
            <div className="run-panel">
              <div className="run-actions">
                <button className="primary" onClick={runTests} disabled={testing}>
                  {testing ? 'Running tests…' : '▶ Run hidden tests'}
                </button>
                <span className="muted small">
                  {modified.length ? `${modified.length} modified file${modified.length > 1 ? 's' : ''}` : 'No changes yet'} · Ctrl/⌘ + Enter
                </span>
                {modified.length > 0 && <button className="link" onClick={() => setCode(original)}>Reset all</button>}
              </div>
              {testError && <p className="error">{testError}</p>}
              {tests && <TestResults result={tests} />}
            </div>
          )}
        </div>

        <aside className="question-col">
          <div className="panel">
            <h2>The task</h2>
            <p className="explanation">{repo.description}</p>

            {!unlocked && (
              <form className="answer" onSubmit={e => { e.preventDefault(); submitReview() }}>
                <h3>Your review</h3>
                <textarea value={review} onChange={e => setReview(e.target.value)} rows={12} maxLength={10000}
                          placeholder={'For each problem: where it is, what goes wrong, and how you would fix it.\n\nCart.java:10 - put() replaces the quantity; use merge(sku, qty, Integer::sum)'} />
                <div className="answer-actions">
                  <button className="primary" type="submit" disabled={grading || !review.trim()}>
                    {grading ? 'Grading…' : 'Submit review'}
                  </button>
                  <button type="button" className="link" onClick={skipReview} disabled={grading}>Skip review</button>
                </div>
                {reviewError && <p className="error">{reviewError}</p>}
                <p className="muted small">
                  After grading, every planted issue is revealed and the code becomes editable.
                  Skipping reveals them without a grade. {review.length} / 10000
                </p>
              </form>
            )}

            {result && <ReviewSummary result={result} onOpen={open} />}

            {!result && repo.issues && (
              <>
                <p className="verdict revealed">Planted issues</p>
                <IssueList items={repo.issues.map(issue => ({ issue }))} onOpen={open} />
              </>
            )}
          </div>
        </aside>
      </div>
    </div>
  )
}

function ReviewSummary({ result, onOpen }: { result: ReviewResult; onOpen: (file: string, line: number) => void }) {
  const all = result.found === result.total
  return (
    <div className="solution">
      <p className={`verdict ${all ? 'right' : result.found ? 'revealed' : 'wrong'}`}>
        Found {result.found} of {result.total} issues · {result.fixesCorrect} correct fix{result.fixesCorrect === 1 ? '' : 'es'}
      </p>
      {result.overallFeedback && <p className="explanation">{result.overallFeedback}</p>}
      <h3>Planted issues</h3>
      <IssueList items={result.issues} onOpen={onOpen} />
      {result.extraFindings.length > 0 && (
        <>
          <h3>Your other points</h3>
          <ul className="issue-list">
            {result.extraFindings.map((f, i) => (
              <li key={i} className={f.valid ? 'found' : 'missed'}>
                <div className="issue-head"><span className="mark">{f.valid ? '✓' : '✗'}</span><b>{f.summary}</b></div>
                <p className="small">{f.feedback}</p>
              </li>
            ))}
          </ul>
        </>
      )}
      <p className="muted small">Now fix the code and run the hidden tests.</p>
    </div>
  )
}

function IssueList({ items, onOpen }: { items: Partial<IssueOutcome>[]; onOpen: (file: string, line: number) => void }) {
  return (
    <ul className="issue-list">
      {items.map(({ issue, found, fixCorrect, feedback }) => issue && (
        <li key={issue.id} className={found === undefined ? '' : found ? 'found' : 'missed'}>
          <div className="issue-head">
            {found !== undefined && <span className="mark">{found ? '✓' : '✗'}</span>}
            <b>{issue.title}</b>
          </div>
          <div className="issue-meta">
            <button className="link" onClick={() => onOpen(issue.file, issue.line)}>{baseName(issue.file)}:{issue.line}</button>
            <span className="tag">{issue.category}</span>
            {found && <span className={`fix ${fixCorrect ? 'ok' : ''}`}>{fixCorrect ? 'fix ✓' : 'no correct fix'}</span>}
          </div>
          {feedback && <p className="small">{feedback}</p>}
          <details>
            <summary className="small muted">Explanation</summary>
            <p className="small">{issue.detail}</p>
          </details>
        </li>
      ))}
    </ul>
  )
}

function TestResults({ result }: { result: TestRunResult }) {
  const { run } = result
  return (
    <div className={`run-output ${result.allPassed ? 'status-ok' : 'status-runtime_error'}`}>
      <div className="run-meta">
        <span className="run-status">
          {result.total === 0 ? 'No tests ran' : result.allPassed ? `All ${result.total} tests pass` : `${result.passed} / ${result.total} tests pass`}
        </span>
        <span>{run.durationMs} ms</span>
        {run.cached && <span className="cached" title="Same code ran before; result served from cache">cached</span>}
        {run.status === 'TIMEOUT' && <span className="cached">timed out</span>}
      </div>
      {result.tests.length > 0 && (
        <ul className="test-list">
          {result.tests.map(t => (
            <li key={t.name} className={t.passed ? 'pass' : 'fail'}>
              <span className="mark">{t.passed ? '✓' : '✗'}</span> {t.name}
              {t.message && <div className="small muted">{t.message}</div>}
            </li>
          ))}
        </ul>
      )}
      {result.allPassed && <p className="verdict right">✓ Fixed! Every hidden test passes.</p>}
      {result.total === 0 && run.stderr && <pre className="out err">{run.stderr}</pre>}
      {result.total === 0 && !run.stderr && run.stdout && <pre className="out">{run.stdout}</pre>}
    </div>
  )
}
