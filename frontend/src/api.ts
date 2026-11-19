export type ExerciseType = 'PREDICT_OUTPUT' | 'TRACE' | 'SPOT_BUG'
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'
export type Progress = 'NEW' | 'ATTEMPTED' | 'SOLVED'
export type RunStatus = 'OK' | 'RUNTIME_ERROR' | 'TIMEOUT' | 'MEMORY_LIMIT' | 'OUTPUT_LIMIT' | 'SANDBOX_ERROR'

export interface ExerciseSummary {
  slug: string
  title: string
  language: string
  type: ExerciseType
  difficulty: Difficulty
  tags: string[]
  status: Progress
  points: number
  maxPoints: number
}

export interface PointsSummary {
  total: number
  snippets: number
  repos: number
}

export interface Solution {
  answer: string
  explanation: string
}

export interface ExerciseView extends Omit<ExerciseSummary, 'status'> {
  code: string
  question: string
  hint: string | null
  solved: boolean
  solution: Solution | null
}

export interface AttemptResult {
  correct: boolean
  attempts: number
  solution: Solution | null
  points: number
}

export interface RunResponse {
  status: RunStatus
  stdout: string
  stderr: string
  exitCode: number
  durationMs: number
  truncated: boolean
  cached: boolean
}

export interface RepoFile {
  path: string
  content: string
}

export interface RepoIssue {
  id: number
  file: string
  line: number
  category: string
  title: string
  detail: string
}

export interface RepoSummary {
  slug: string
  title: string
  language: string
  difficulty: Difficulty
  tags: string[]
  files: number
  issues: number
  status: Progress
  points: number
  maxPoints: number
}

export interface RepoView extends Omit<RepoSummary, 'files' | 'issues' | 'status'> {
  description: string
  files: RepoFile[]
  revealed: boolean
  issues: RepoIssue[] | null
  questionsLeft: number
}

export interface IssueOutcome {
  issue: RepoIssue
  found: boolean
  fixCorrect: boolean
  feedback: string
}

export interface ReviewResult {
  found: number
  total: number
  fixesCorrect: number
  issues: IssueOutcome[]
  extraFindings: { summary: string; valid: boolean; feedback: string }[]
  overallFeedback: string
}

export interface TestCase {
  name: string
  passed: boolean
  message: string | null
}

export interface TestRunResult {
  tests: TestCase[]
  passed: number
  total: number
  allPassed: boolean
  run: RunResponse
  points: number
  maxPoints: number
}

export interface TestRunSummary {
  id: number
  createdAt: string
  passed: number
  total: number
  allPassed: boolean
  tests: TestCase[]
}

export interface EditResult {
  replacement: string
  note: string | null
}

export interface QuestionAnswer {
  answer: string
  questionsLeft: number
}

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export interface Me {
  user: { name: string | null; email: string | null; picture: string | null } | null
  devHeader: boolean
  googleConfigured: boolean
}

/** Fired on window when the server says the session is gone (401), so the app can show the sign-in screen. */
export const SIGNED_OUT_EVENT = 'codecomp:signed-out'

// Dev and test only: when the backend runs with AUTH_DEV_HEADER, a random id in localStorage stands in for sign-in.
let devHeader = false
export const setDevHeaderMode = (on: boolean) => { devHeader = on }

const USER_KEY = 'codecomp.user'

function devUserId(): string {
  let id = localStorage.getItem(USER_KEY)
  if (!id) {
    id = crypto.randomUUID?.() ??
      '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, c =>
        (+c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (+c / 4)))).toString(16))
    localStorage.setItem(USER_KEY, id)
  }
  return id
}

/** The CSRF token the server keeps in a readable cookie; it must be echoed in a header on every POST. */
function csrfToken(): string | undefined {
  return document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length)
}

function authHeaders(): Record<string, string> {
  return devHeader ? { 'X-User-Id': devUserId() } : {}
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...authHeaders() }
  const token = csrfToken()
  if (token && init.method && init.method !== 'GET') headers['X-XSRF-TOKEN'] = decodeURIComponent(token)
  const res = await fetch(`/api${path}`, { ...init, headers: { ...headers, ...init.headers } })
  if (res.status === 401 && !path.startsWith('/auth/')) window.dispatchEvent(new Event(SIGNED_OUT_EVENT))
  if (res.status === 204) return undefined as T
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new ApiError(res.status, body?.detail ?? body?.message ?? res.statusText)
  }
  return res.json() as Promise<T>
}

/** Fired on window whenever an action may have changed the user's points, so the top bar can refresh its total. */
export const POINTS_EVENT = 'codecomp:points'
export const notifyPointsChanged = () => window.dispatchEvent(new Event(POINTS_EVENT))
const pointsMayHaveChanged = <T,>(result: T): T => {
  notifyPointsChanged()
  return result
}

export type JobStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'

/** A queued code run. `id` is null when the result came straight from the cache. */
export interface JobView<T> {
  id: string | null
  status: JobStatus
  position: number | null
  result: T | null
  error: string | null
}

/** Button text while waiting for a run. */
export const waitingLabel = (job: JobView<unknown> | null, running: string) =>
  job?.status === 'QUEUED' ? (job.position ? `Queued, #${job.position} in line` : 'Queued') : running

/**
 * Resolves with the result of a submitted run. Progress (place in the queue, running) arrives over Server-Sent Events.
 * The stream is read with fetch rather than EventSource so it carries the same headers as every other request.
 * If the stream drops before the job finishes it reconnects; the server sends the current state first, so nothing is missed.
 */
export async function followJob<T>(job: JobView<T>, onUpdate?: (job: JobView<T>) => void): Promise<T> {
  let current = job
  for (let attempt = 0; attempt < 30; attempt++) {
    if (current.status === 'DONE') return current.result as T
    if (current.status === 'FAILED') throw new ApiError(500, current.error ?? 'The run failed')
    onUpdate?.(current)
    try {
      current = await readJobEvents(current.id!, current, update => {
        current = update
        if (update.status === 'QUEUED' || update.status === 'RUNNING') onUpdate?.(update)
      })
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.status === 403 || e.status === 404)) throw e
      await new Promise(resolve => setTimeout(resolve, Math.min(500 * (attempt + 1), 3000)))
    }
  }
  throw new ApiError(504, 'Stopped waiting for the run. Reload the page to see whether it finished.')
}

async function readJobEvents<T>(id: string, latest: JobView<T>, onEvent: (job: JobView<T>) => void): Promise<JobView<T>> {
  const res = await fetch(`/api/jobs/${encodeURIComponent(id)}/events`, { headers: { Accept: 'text/event-stream', ...authHeaders() } })
  if (res.status === 401) window.dispatchEvent(new Event(SIGNED_OUT_EVENT))
  if (!res.ok || !res.body) throw new ApiError(res.status, res.statusText)
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) return latest
    buffer += value
    // events are separated by a blank line; each carries the job as JSON on its data: line
    for (let end = buffer.indexOf('\n\n'); end >= 0; end = buffer.indexOf('\n\n')) {
      const data = buffer.slice(0, end).split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5)).join('\n')
      buffer = buffer.slice(end + 2)
      if (data) {
        latest = JSON.parse(data) as JobView<T>
        onEvent(latest)
      }
    }
  }
}

export const api = {
  me: () => request<Me>('/auth/me'),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),
  points: () => request<PointsSummary>('/points'),
  exercises: () => request<ExerciseSummary[]>('/exercises'),
  exercise: (slug: string) => request<ExerciseView>(`/exercises/${encodeURIComponent(slug)}`),
  attempt: (slug: string, body: { answer?: string; giveUp?: boolean }) =>
    request<AttemptResult>(`/exercises/${encodeURIComponent(slug)}/attempts`, { method: 'POST', body: JSON.stringify(body) })
      .then(pointsMayHaveChanged),
  /** Answered at once for code that ran before; otherwise a queued job to pass to followJob. */
  run: (language: string, code: string) =>
    request<JobView<RunResponse>>('/runs', { method: 'POST', body: JSON.stringify({ language, code }) }),
  edit: (language: string, code: string, selection: string, instruction: string) =>
    request<EditResult>('/edits', { method: 'POST', body: JSON.stringify({ language, code, selection, instruction }) }),
  repos: () => request<RepoSummary[]>('/repos'),
  repo: (slug: string) => request<RepoView>(`/repos/${encodeURIComponent(slug)}`),
  review: (slug: string, review: string) =>
    request<ReviewResult>(`/repos/${encodeURIComponent(slug)}/reviews`, { method: 'POST', body: JSON.stringify({ review }) }),
  reveal: (slug: string) => request<RepoView>(`/repos/${encodeURIComponent(slug)}/reveal`, { method: 'POST' }),
  testRuns: (slug: string) => request<TestRunSummary[]>(`/repos/${encodeURIComponent(slug)}/test-runs`),
  ask: (slug: string, question: string) =>
    request<QuestionAnswer>(`/repos/${encodeURIComponent(slug)}/questions`, { method: 'POST', body: JSON.stringify({ question }) }),
  testRun: (slug: string, files: Record<string, string>) =>
    request<JobView<TestRunResult>>(`/repos/${encodeURIComponent(slug)}/test-runs`, { method: 'POST', body: JSON.stringify({ files }) }),
}

export const TYPE_LABEL: Record<ExerciseType, string> = {
  PREDICT_OUTPUT: 'Predict output',
  TRACE: 'Trace state',
  SPOT_BUG: 'Spot the bug',
}
