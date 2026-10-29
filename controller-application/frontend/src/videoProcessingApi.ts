export type ProxyJob = {
  jobId: string
  gameName: string
  state: 'running' | 'completed' | 'failed'
  message: string
  resultsAvailable?: boolean
  samplingIntervalSeconds?: number | null
  videos: { name: string; inputPath: string; outputPath: string; framesDirectory?: string | null; bossDetectionsPath?: string | null; bossRefinementDirectory?: string | null }[]
}

export type ProxyEvent = {
  id: number
  jobId: string
  state: 'started' | 'running' | 'completed' | 'failed' | 'warning' | 'interval' | 'chapters_ready' | 'job_started' | 'job_completed' | 'job_failed'
  message: string
  resultsAvailable?: boolean
  videoIndex?: number
  videoName?: string
  operation?: 'proxy' | 'sample' | 'boss-detection' | 'boss-refinement'
  stepSeconds?: number
  windowIndex?: number
  totalWindows?: number
  windowStartMs?: number
  windowEndMs?: number
  phase?: 'sampling' | 'detection'
  commandLine?: string
  fps?: number | null
  speed?: number | null
  percent?: number | null
  source_seconds?: number | null
  duration_seconds?: number | null
  outputVideo?: string
  outputDirectory?: string
  outputFile?: string
  frame_count?: number | null
  total_frames?: number | null
  detail?: string
}

async function readJob(response: Response): Promise<ProxyJob> {
  let body
  try { body = await response.json() }
  catch { throw new Error(`Controller returned an unreadable response (HTTP ${response.status}).`) }
  if (!response.ok) throw new Error(body.message || body.detail || `Controller returned HTTP ${response.status}.`)
  if (typeof body.jobId !== 'string' || !['running', 'completed', 'failed'].includes(body.state)) {
    throw new Error('Controller returned an invalid job response.')
  }
  return body
}

export async function startProxyJob(gameName: string, videos: string[]): Promise<ProxyJob> {
  return readJob(await fetch('/api/chapters/jobs', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ gameName, videos }),
  }))
}

export async function getProxyJob(id: string): Promise<ProxyJob> {
  return readJob(await fetch(`/api/chapters/jobs/${encodeURIComponent(id)}`))
}

export type ChapterResults = {
  jobId: string
  gameName: string
  videos: { videoIndex: number; videoName: string; durationMs: number; text: string; warnings: string[] }[]
}

export async function getChapterResults(id: string, signal?: AbortSignal): Promise<ChapterResults> {
  const response = await fetch(`/api/chapters/jobs/${encodeURIComponent(id)}/results`, { signal })
  let body
  try { body = await response.json() }
  catch { throw new Error(`Controller returned an unreadable result (HTTP ${response.status}).`) }
  if (!response.ok) throw new Error(body.message || `Could not load results (HTTP ${response.status}).`)
  if (!Array.isArray(body.videos) || body.videos.length === 0 || body.videos.some((video: ChapterResults['videos'][number]) =>
    typeof video.text !== 'string' || typeof video.videoName !== 'string' || !Array.isArray(video.warnings))) {
    throw new Error('Controller returned invalid chapter results.')
  }
  return body
}

export function watchProxyJob(id: string, receive: (event: ProxyEvent) => void,
                             disconnected: () => void, connected: () => void): EventSource {
  const source = new EventSource(`/api/chapters/jobs/${encodeURIComponent(id)}/events`)
  source.addEventListener('processing', event => receive(JSON.parse(event.data) as ProxyEvent))
  source.onerror = disconnected
  source.onopen = connected
  return source
}

export function formatProgress(event: ProxyEvent): string {
  const stage = event.operation === 'boss-refinement' ? `Boss boundary refinement (${event.stepSeconds ?? '?'} s, ${event.phase ?? 'analysis'})`
    : event.operation === 'boss-detection' ? 'Boss fight detection'
    : event.operation === 'sample' ? 'Frame sampling' : 'Proxy'
  const parts = [event.videoName || 'Video', stage]
  if (event.windowIndex != null) parts.push(`window ${event.windowIndex} / ${event.totalWindows ?? '?'}`)
  if (event.windowStartMs != null && event.windowEndMs != null) parts.push(`${event.windowStartMs / 1000}–${event.windowEndMs / 1000} s`)
  if (event.percent != null) parts.push(`${event.percent.toFixed(1)}%`)
  if (event.source_seconds != null) parts.push(event.operation === 'boss-detection' || event.operation === 'boss-refinement'
    ? `source ${event.source_seconds.toFixed(1)} s`
    : `${event.source_seconds.toFixed(1)} / ${event.duration_seconds?.toFixed(1) ?? '?'} s`)
  if (event.operation !== 'proxy' && event.frame_count != null) parts.push(`frames ${event.frame_count} / ${event.total_frames ?? '?'}`)
  if (event.fps != null) parts.push(`${event.fps.toFixed(1)} fps`)
  if (event.speed != null) parts.push(`${event.speed.toFixed(2)}x`)
  return parts.join(' | ')
}
