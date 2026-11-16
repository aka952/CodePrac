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
}

export interface RepoView extends Omit<RepoSummary, 'files' | 'issues' | 'status'> {
  description: string
  files: RepoFile[]
  revealed: boolean
  issues: RepoIssue[] | null
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

export interface TestRunResult {
  tests: { name: string; passed: boolean; message: string | null }[]
  passed: number
  total: number
  allPassed: boolean
  run: RunResponse
}

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

const USER_KEY = 'codecomp.user'

function userId(): string {
  let id = localStorage.getItem(USER_KEY)
  if (!id) {
    id = crypto.randomUUID?.() ??
      '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, c =>
        (+c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (+c / 4)))).toString(16))
    localStorage.setItem(USER_KEY, id)
  }
  return id
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId(), ...init.headers },
  })
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new ApiError(res.status, body?.detail ?? body?.message ?? res.statusText)
  }
  return res.json() as Promise<T>
}

export const api = {
  exercises: () => request<ExerciseSummary[]>('/exercises'),
  exercise: (slug: string) => request<ExerciseView>(`/exercises/${encodeURIComponent(slug)}`),
  attempt: (slug: string, body: { answer?: string; giveUp?: boolean }) =>
    request<AttemptResult>(`/exercises/${encodeURIComponent(slug)}/attempts`, { method: 'POST', body: JSON.stringify(body) }),
  run: (language: string, code: string) =>
    request<RunResponse>('/runs', { method: 'POST', body: JSON.stringify({ language, code }) }),
  repos: () => request<RepoSummary[]>('/repos'),
  repo: (slug: string) => request<RepoView>(`/repos/${encodeURIComponent(slug)}`),
  review: (slug: string, review: string) =>
    request<ReviewResult>(`/repos/${encodeURIComponent(slug)}/reviews`, { method: 'POST', body: JSON.stringify({ review }) }),
  reveal: (slug: string) => request<RepoView>(`/repos/${encodeURIComponent(slug)}/reveal`, { method: 'POST' }),
  testRun: (slug: string, files: Record<string, string>) =>
    request<TestRunResult>(`/repos/${encodeURIComponent(slug)}/test-runs`, { method: 'POST', body: JSON.stringify({ files }) }),
}

export const TYPE_LABEL: Record<ExerciseType, string> = {
  PREDICT_OUTPUT: 'Predict output',
  TRACE: 'Trace state',
  SPOT_BUG: 'Spot the bug',
}
