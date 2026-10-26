export type ProxyJob = {
  jobId: string
  gameName: string
  state: 'running' | 'completed' | 'failed'
  message: string
  samplingIntervalSeconds?: number | null
  videos: { name: string; inputPath: string; outputPath: string; framesDirectory?: string | null }[]
}

export type ProxyEvent = {
  id: number
  jobId: string
  state: 'started' | 'running' | 'completed' | 'failed' | 'job_started' | 'job_completed' | 'job_failed'
  message: string
  videoIndex?: number
  videoName?: string
  operation?: 'proxy' | 'sample'
  commandLine?: string
  fps?: number | null
  speed?: number | null
  percent?: number | null
  source_seconds?: number | null
  duration_seconds?: number | null
  outputVideo?: string
  outputDirectory?: string
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

export function watchProxyJob(id: string, receive: (event: ProxyEvent) => void,
                             disconnected: () => void, connected: () => void): EventSource {
  const source = new EventSource(`/api/chapters/jobs/${encodeURIComponent(id)}/events`)
  source.addEventListener('processing', event => receive(JSON.parse(event.data) as ProxyEvent))
  source.onerror = disconnected
  source.onopen = connected
  return source
}

export function formatProgress(event: ProxyEvent): string {
  const parts = [event.videoName || 'Video', event.operation === 'sample' ? 'Frame sampling' : 'Proxy']
  if (event.percent != null) parts.push(`${event.percent.toFixed(1)}%`)
  if (event.source_seconds != null) parts.push(`${event.source_seconds.toFixed(1)} / ${event.duration_seconds?.toFixed(1) ?? '?'} s`)
  if (event.operation === 'sample' && event.frame_count != null) parts.push(`frames ${event.frame_count} / ${event.total_frames ?? '?'}`)
  if (event.fps != null) parts.push(`${event.fps.toFixed(1)} fps`)
  if (event.speed != null) parts.push(`${event.speed.toFixed(2)}x`)
  return parts.join(' | ')
}
