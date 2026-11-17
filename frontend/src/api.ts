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

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (devHeader) headers['X-User-Id'] = devUserId()
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
const pointsMayHaveChanged = <T,>(result: T): T => {
  window.dispatchEvent(new Event(POINTS_EVENT))
  return result
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
  run: (language: string, code: string) =>
    request<RunResponse>('/runs', { method: 'POST', body: JSON.stringify({ language, code }) }),
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
    request<TestRunResult>(`/repos/${encodeURIComponent(slug)}/test-runs`, { method: 'POST', body: JSON.stringify({ files }) })
      .then(pointsMayHaveChanged),
}

export const TYPE_LABEL: Record<ExerciseType, string> = {
  PREDICT_OUTPUT: 'Predict output',
  TRACE: 'Trace state',
  SPOT_BUG: 'Spot the bug',
}
