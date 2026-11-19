import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  api, followJob, type IssueOutcome, type JobView, notifyPointsChanged, type RepoIssue, type RepoSummary, type RepoView,
  type ReviewResult, type RunResponse, type TestCase, type TestRunResult, type TestRunSummary, waitingLabel,
} from '../api'
import CodeEditor from '../components/CodeEditor'

const baseName = (path: string) => path.slice(path.lastIndexOf('/') + 1)

interface Draft { code?: Record<string, string>; review?: string }
const draftKey = (slug: string) => `codecomp.repo.${slug}`

function loadDraft(slug: string): Draft {
  try {
    return JSON.parse(localStorage.getItem(draftKey(slug)) ?? '{}') ?? {}
  } catch {
    return {}
  }
}

function saveDraft(slug: string, draft: Draft) {
  try {
    localStorage.setItem(draftKey(slug), JSON.stringify(draft))
  } catch {
    // storage unavailable (private window, quota): the page still works, edits just won't survive a reload
  }
}

interface TreeNode { name: string; path?: string; children: TreeNode[] }

/** Folder tree from file paths, with single-child folder chains merged into one row (src/main/java/com/shop). */
function buildTree(paths: string[]): TreeNode[] {
  const root: TreeNode = { name: '', children: [] }
  for (const path of paths) {
    const parts = path.split('/')
    let node = root
    parts.forEach((name, i) => {
      let child = node.children.find(c => c.name === name && !c.path)
      if (i === parts.length - 1) child = { name, path, children: [] }
      else if (!child) child = { name, children: [] }
      if (!node.children.includes(child)) node.children.push(child)
      node = child
    })
  }
  const merge = (n: TreeNode): TreeNode => {
    let cur = n
    while (!cur.path && cur.children.length === 1 && !cur.children[0].path) {
      cur = { name: `${cur.name}/${cur.children[0].name}`, children: cur.children[0].children }
    }
    return { ...cur, children: cur.children.map(merge) }
  }
  return root.children.map(merge)
}

/** What the results box shows: a fresh run (with timing) or one picked from the history. */
interface ShownRun {
  tests: TestCase[]; passed: number; total: number; allPassed: boolean
  run?: RunResponse; at?: string; points?: number; maxPoints?: number
}

const when = (iso: string) => new Date(iso).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })

export default function RepoPage() {
  const { slug = '' } = useParams()
  const [repo, setRepo] = useState<RepoView | null>(null)
  const [all, setAll] = useState<RepoSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [leftTab, setLeftTab] = useState<'statement' | 'files'>('statement')
  const [rightTab, setRightTab] = useState<'review' | 'ask'>('review')
  const [tabs, setTabs] = useState<string[]>([])
  const [active, setActive] = useState('')
  const [code, setCode] = useState<Record<string, string>>({})
  const [focus, setFocus] = useState<{ file: string; line: number } | null>(null)
  const [review, setReview] = useState('')
  const [result, setResult] = useState<ReviewResult | null>(null)
  const [grading, setGrading] = useState(false)
  const [reviewError, setReviewError] = useState<string | null>(null)
  const [shown, setShown] = useState<ShownRun | null>(null)
  const [runs, setRuns] = useState<TestRunSummary[]>([])
  const [showRuns, setShowRuns] = useState(false)
  const [testing, setTesting] = useState(false)
  const [progress, setProgress] = useState<JobView<TestRunResult> | null>(null)
  const [testError, setTestError] = useState<string | null>(null)
  const [questionsLeft, setQuestionsLeft] = useState(0)
  const [points, setPoints] = useState(0)

  useEffect(() => {
    setRepo(null); setError(null); setResult(null); setShown(null); setFocus(null); setRuns([]); setShowRuns(false)
    setLeftTab('statement'); setRightTab('review')
    api.repo(slug).then(r => {
      const draft = loadDraft(slug)
      const files = Object.fromEntries(r.files.map(f => [f.path, f.content]))
      // saved edits only apply once fixing is unlocked, and only to files that still exist
      if (r.revealed) for (const [path, text] of Object.entries(draft.code ?? {})) if (path in files) files[path] = text
      const first = r.files[0]?.path ?? ''
      setRepo(r)
      setCode(files)
      setReview(r.revealed ? '' : draft.review ?? '')
      setTabs(first ? [first] : [])
      setActive(first)
      setQuestionsLeft(r.questionsLeft)
      setPoints(r.points)
    }).catch(e => setError(e.message))
    api.repos().then(setAll).catch(() => setAll([]))
    api.testRuns(slug).then(setRuns).catch(() => setRuns([]))
  }, [slug])

  const original = useMemo(() => Object.fromEntries((repo?.files ?? []).map(f => [f.path, f.content])), [repo])
  const tree = useMemo(() => buildTree(repo?.files.map(f => f.path) ?? []), [repo])
  const modified = useMemo(() => Object.keys(code).filter(p => code[p] !== original[p]), [code, original])

  useEffect(() => {
    if (repo?.slug === slug) saveDraft(slug, { code: Object.fromEntries(modified.map(p => [p, code[p]])), review })
  }, [repo, slug, code, modified, review])

  const next = useMemo(() => {
    const i = all.findIndex(a => a.slug === slug)
    return i >= 0 ? all[i + 1] : undefined
  }, [all, slug])

  if (error) return <p className="error">{error} <Link to="/repos">Back to repo reviews</Link></p>
  if (!repo) return <p className="muted">Loading…</p>

  const issues: RepoIssue[] | null = result?.issues.map(o => o.issue) ?? repo.issues
  const unlocked = issues !== null
  const issueCount = (path: string) => issues?.filter(i => i.file === path).length ?? 0

  const openFile = (file: string, line?: number) => {
    setTabs(t => (t.includes(file) ? t : [...t, file]))
    setActive(file)
    setFocus(line ? { file, line } : null)
  }

  const closeTab = (file: string) => {
    const rest = tabs.filter(t => t !== file)
    setTabs(rest)
    if (active === file) setActive(rest[Math.min(tabs.indexOf(file), rest.length - 1)] ?? '')
  }

  const cite = (line: number) => {
    setReview(r => `${r}${r && !r.endsWith('\n') ? '\n' : ''}${baseName(active)}:${line} - `)
    setFocus({ file: active, line })
    setRightTab('review')
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
    if (testing || !unlocked) return
    setTesting(true)
    setTestError(null)
    try {
      const run = await followJob(await api.testRun(slug, Object.fromEntries(modified.map(p => [p, code[p]]))), setProgress)
      notifyPointsChanged()
      setShown(run)
      setPoints(run.points)
      api.testRuns(slug).then(setRuns).catch(() => {})
    } catch (e) {
      setTestError((e as Error).message)
    } finally {
      setTesting(false)
      setProgress(null)
    }
  }

  const badges = (path: string) => (
    <>
      {modified.includes(path) && <span className="modified" title="Modified">●</span>}
      {issueCount(path) > 0 && <span className="issue-count" title="Planted issues in this file">{issueCount(path)}</span>}
    </>
  )

  const renderTree = (nodes: TreeNode[], depth = 0): React.ReactNode => nodes.map(n => n.path ? (
    <button key={n.path} className={`tree-file${n.path === active ? ' active' : ''}`} style={{ paddingLeft: 10 + depth * 14 }}
            onClick={() => openFile(n.path!)}>
      {n.name}{badges(n.path)}
    </button>
  ) : (
    <div key={n.name}>
      <div className="tree-dir" style={{ paddingLeft: 10 + depth * 14 }}>{n.name}/</div>
      {renderTree(n.children, depth + 1)}
    </div>
  ))

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
          <span className={`pts${points ? ' earned' : ''}`}>{points ? `${points} / ${repo.maxPoints}` : `up to ${repo.maxPoints}`} pts</span>
          {repo.tags.map(t => <span key={t} className="tag">#{t}</span>)}
        </div>
      </div>

      <div className="workspace ide">
        <aside className="panel">
          <div className="tabs">
            <button className={leftTab === 'statement' ? 'active' : ''} onClick={() => setLeftTab('statement')}>Statement</button>
            <button className={leftTab === 'files' ? 'active' : ''} onClick={() => setLeftTab('files')}>
              Files{modified.length > 0 && <span className="modified">●</span>}
            </button>
          </div>
          {leftTab === 'statement' ? (
            <>
              <p className="explanation">{repo.description}</p>
              <p className="muted small">
                Worth up to {repo.maxPoints} points: 80% is shared across the hidden tests you fix, and 20% is a bonus for passing them all.
                Your best run counts.
              </p>
              {next && (
                <Link to={`/repo/${next.slug}`} className="upnext">
                  <span className="muted small">Up next</span>
                  <b>{next.title} →</b>
                  <span className="muted small">{next.language} · {next.difficulty.toLowerCase()} · {next.files} files</span>
                </Link>
              )}
            </>
          ) : (
            <div className="tree">{renderTree(tree)}</div>
          )}
        </aside>

        <div className="code-col">
          <div className="editor-tabs">
            {tabs.map(t => (
              <span key={t} className={`editor-tab${t === active ? ' active' : ''}`}>
                <button onClick={() => openFile(t)}>{baseName(t)}{badges(t)}</button>
                <button className="close" title="Close" aria-label={`Close ${baseName(t)}`} onClick={() => closeTab(t)}>×</button>
              </span>
            ))}
          </div>
          {active ? (
            <>
              <div className="code-bar">
                <span>{active}</span>
                {unlocked
                  ? modified.includes(active) && <button className="link" onClick={() => setCode(c => ({ ...c, [active]: original[active] }))}>Reset file</button>
                  : <span>Read only. Click a line to cite it in your review</span>}
              </div>
              {/* keyed by file: one editor instance per file, so switching files can never write one file's text into another */}
              <CodeEditor
                key={active}
                language={repo.language}
                value={code[active] ?? ''}
                readOnly={!unlocked}
                onChange={v => setCode(c => ({ ...c, [active]: v }))}
                selectableLines={!unlocked}
                selectedLine={!unlocked && focus?.file === active ? focus.line : null}
                answerLine={unlocked && focus?.file === active ? focus.line : null}
                onSelectLine={cite}
                onRun={runTests}
                onAiEdit={(selection, instruction) => api.edit(repo.language, code[active] ?? '', selection, instruction)}
              />
            </>
          ) : (
            <p className="muted empty-editor">No file open. Pick one from the <button className="link" onClick={() => setLeftTab('files')}>Files</button> tab.</p>
          )}

          {unlocked && (
            <div className="run-panel">
              <div className="run-actions">
                <button className="primary" onClick={runTests} disabled={testing}>
                  {testing ? waitingLabel(progress, 'Running tests…') : '▶ Run hidden tests'}
                </button>
                {runs.length > 0 && (
                  <button onClick={() => setShowRuns(s => !s)}>Previous runs ({runs.length})</button>
                )}
                <span className="muted small">
                  {modified.length ? `${modified.length} modified file${modified.length > 1 ? 's' : ''}` : 'No changes yet'} · Ctrl/⌘ + Enter
                </span>
                {modified.length > 0 && <button className="link" onClick={() => setCode(original)}>Reset all</button>}
              </div>
              {showRuns && (
                <ul className="runs">
                  {runs.map(r => (
                    <li key={r.id}>
                      <button className={shown?.at === r.createdAt ? 'active' : ''} onClick={() => setShown({ ...r, at: r.createdAt })}>
                        <span className={r.allPassed ? 'pass' : 'fail'}><span className="mark">{r.allPassed ? '✓' : '✗'}</span></span>
                        {when(r.createdAt)}
                        <span className="muted">{r.total ? `${r.passed} / ${r.total} passed` : 'did not compile or run'}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              {testError && <p className="error">{testError}</p>}
              {shown && <TestResults shown={shown} />}
            </div>
          )}
          <p className="muted small saved-note">
            Your edits and review draft are saved in this browser.
            {unlocked && ' Select code to describe a change and have AI write it.'}
          </p>
        </div>

        <aside className="panel">
          <div className="tabs">
            <button className={rightTab === 'review' ? 'active' : ''} onClick={() => setRightTab('review')}>Review</button>
            <button className={rightTab === 'ask' ? 'active' : ''} onClick={() => setRightTab('ask')}>Ask AI</button>
          </div>

          {rightTab === 'ask' && <AskPanel slug={slug} left={questionsLeft} onLeft={setQuestionsLeft} />}

          {rightTab === 'review' && !unlocked && (
            <form className="answer" onSubmit={e => { e.preventDefault(); submitReview() }}>
              <textarea value={review} onChange={e => setReview(e.target.value)} rows={14} maxLength={10000}
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

          {rightTab === 'review' && result && <ReviewSummary result={result} onOpen={openFile} />}

          {rightTab === 'review' && !result && repo.issues && (
            <>
              <p className="verdict revealed">Planted issues</p>
              <IssueList items={repo.issues.map(issue => ({ issue }))} onOpen={openFile} />
            </>
          )}
        </aside>
      </div>
    </div>
  )
}

function AskPanel({ slug, left, onLeft }: { slug: string; left: number; onLeft: (n: number) => void }) {
  const [question, setQuestion] = useState('')
  const [log, setLog] = useState<{ q: string; a: string }[]>([])
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const ask = async () => {
    const q = question.trim()
    if (!q || asking || left === 0) return
    setAsking(true)
    setError(null)
    try {
      const res = await api.ask(slug, q)
      setLog(l => [...l, { q, a: res.answer }])
      onLeft(res.questionsLeft)
      setQuestion('')
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setAsking(false)
    }
  }

  return (
    <div className="ask">
      <p className="muted small">
        Syntax and language questions only, for example "how does equals differ from == for strings in Java?".
        The assistant has not seen this exercise and won't help solve it.
      </p>
      {log.map((entry, i) => (
        <div key={i} className="ask-entry">
          <p className="ask-q">{entry.q}</p>
          <p className="ask-a">{entry.a}</p>
        </div>
      ))}
      <form className="answer" onSubmit={e => { e.preventDefault(); ask() }}>
        <textarea value={question} onChange={e => setQuestion(e.target.value)} rows={3} maxLength={1000}
                  placeholder="Ask a syntax question…" disabled={left === 0} />
        <div className="answer-actions">
          <button className="primary" type="submit" disabled={asking || left === 0 || !question.trim()}>
            {asking ? 'Asking…' : 'Ask'}
          </button>
          <span className="muted small">{left} left for this exercise</span>
        </div>
        {error && <p className="error">{error}</p>}
      </form>
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

function TestResults({ shown }: { shown: ShownRun }) {
  const { run } = shown
  return (
    <div className={`run-output ${shown.allPassed ? 'status-ok' : 'status-runtime_error'}`}>
      <div className="run-meta">
        <span className="run-status">
          {shown.total === 0 ? 'No tests ran' : shown.allPassed ? `All ${shown.total} tests pass` : `${shown.passed} / ${shown.total} tests pass`}
        </span>
        {shown.points !== undefined && <span>{shown.points} / {shown.maxPoints} pts</span>}
        {run && <span>{run.durationMs} ms</span>}
        {run?.cached && <span className="cached" title="Same code ran before; result served from cache">cached</span>}
        {run?.status === 'TIMEOUT' && <span className="cached">timed out</span>}
        {shown.at && <span>earlier run, {when(shown.at)}</span>}
      </div>
      {shown.tests.length > 0 && (
        <ul className="test-list">
          {shown.tests.map(t => (
            <li key={t.name} className={t.passed ? 'pass' : 'fail'}>
              <span className="mark">{t.passed ? '✓' : '✗'}</span> {t.name}
              {t.message && <div className="small muted">{t.message}</div>}
            </li>
          ))}
        </ul>
      )}
      {shown.allPassed && !shown.at && <p className="verdict right">✓ Fixed! Every hidden test passes.</p>}
      {shown.total === 0 && run?.stderr && <pre className="out err">{run.stderr}</pre>}
      {shown.total === 0 && run && !run.stderr && run.stdout && <pre className="out">{run.stdout}</pre>}
    </div>
  )
}
