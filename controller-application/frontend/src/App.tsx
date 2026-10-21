import { useEffect, useRef, useState } from 'react'
import type { DragEvent, FormEvent, ReactNode, SVGProps } from 'react'
import { buildKnowledgeBase } from './knowledgeBaseApi'
import './App.css'

type Action = 'knowledge' | 'chapters'
type LogLevel = 'info' | 'success' | 'warning' | 'error'
type LogEntry = { id: number; timestamp: Date; level: LogLevel; message: string }
type VideoFile = { id: string; file: File }

const actionLabels: Record<Action, string> = {
  knowledge: 'Build Knowledge Base',
  chapters: 'Build Chapters',
}
const videoExtensions = ['mp4', 'mkv', 'mov', 'avi', 'webm', 'm4v', 'mpeg', 'mpg', 'ts', 'mts', 'm2ts', 'wmv', 'flv']
const videoAccept = ['video/*', ...videoExtensions.map(extension => `.${extension}`)].join(',')
// Keep a long session's UI log bounded until persistent job logging is connected.
const maxLogEntries = 500

function Icon({ name, ...props }: { name: 'play' | 'book' | 'video' | 'upload' | 'close' | 'terminal' } & SVGProps<SVGSVGElement>) {
  const paths: Record<typeof name, ReactNode> = {
    play: <path d="m9 5 11 7-11 7V5Z" />,
    book: <path d="M12 6v15M3 3h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5v16h-5a4 4 0 0 0-4 2 4 4 0 0 0-4-2H3V3Z" />,
    video: <><rect x="3" y="4" width="18" height="16" rx="3" /><path d="m10 9 5 3-5 3V9ZM7 4v16M17 4v16" /></>,
    upload: <path d="M12 16V3m-5 5 5-5 5 5M4 15v5a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5" />,
    close: <path d="m6 6 12 12M6 18 18 6" />,
    terminal: <path d="m4 6 5 6-5 6m9 0h7" />,
  }
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths[name]}</svg>
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length)
  return `${(bytes / 1024 ** exponent).toFixed(1)} ${units[exponent - 1]}`
}

export default function App() {
  const [gameName, setGameName] = useState('')
  const [action, setAction] = useState<Action>('knowledge')
  const [videos, setVideos] = useState<VideoFile[]>([])
  const [dragging, setDragging] = useState(false)
  const [isBuilding, setIsBuilding] = useState(false)
  const buildInFlight = useRef(false)
  const [logs, setLogs] = useState<LogEntry[]>(() => [
    { id: 0, timestamp: new Date(), level: 'info', message: 'Workspace ready. Enter a game name and choose an action.' },
  ])
  const nextLogId = useRef(1)
  const dragDepth = useRef(0)
  const logPanel = useRef<HTMLDivElement>(null)
  const followLogs = useRef(true)

  useEffect(() => {
    // A file dropped outside the picker must not navigate away from the workspace.
    const preventFileNavigation = (event: globalThis.DragEvent) => {
      if (event.dataTransfer?.types.includes('Files')) event.preventDefault()
    }
    window.addEventListener('dragover', preventFileNavigation)
    window.addEventListener('drop', preventFileNavigation)
    return () => {
      window.removeEventListener('dragover', preventFileNavigation)
      window.removeEventListener('drop', preventFileNavigation)
    }
  }, [])

  useEffect(() => {
    if (followLogs.current && logPanel.current) {
      logPanel.current.scrollTop = logPanel.current.scrollHeight
    }
  }, [logs])

  function log(level: LogLevel, message: string) {
    const entry = { id: nextLogId.current++, timestamp: new Date(), level, message }
    setLogs(previous => [...previous, entry].slice(-maxLogEntries))
  }

  function selectAction(value: Action) {
    setAction(value)
    setDragging(false)
    dragDepth.current = 0
    log('info', `Action selected: ${actionLabels[value]}.`)
  }

  function addVideos(files: FileList | File[]) {
    const next = [...videos]
    for (const file of Array.from(files)) {
      const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
      if (!file.type.startsWith('video/') && !videoExtensions.includes(extension)) {
        log('warning', `Skipped "${file.name}": select a video file.`)
        continue
      }
      if (file.size === 0) {
        log('warning', `Skipped "${file.name}": the file is empty.`)
        continue
      }
      const id = JSON.stringify([file.name, file.size, file.lastModified])
      if (next.some(video => video.id === id)) {
        log('warning', `Skipped "${file.name}": already selected.`)
        continue
      }
      next.push({ id, file })
      log('success', `Added "${file.name}" (${formatSize(file.size)}).`)
    }
    // Store references and metadata only; multi-hour recordings are never read here.
    setVideos(next)
  }

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    dragDepth.current = 0
    setDragging(false)
    addVideos(event.dataTransfer.files)
  }

  async function startKnowledge(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const requestedGame = gameName.trim()
    if (action !== 'knowledge' || !requestedGame || buildInFlight.current) return
    buildInFlight.current = true
    setIsBuilding(true)
    log('info', `Requesting game knowledge for "${requestedGame}". Waiting for LM Studio; this may take several minutes.`)
    try {
      const result = await buildKnowledgeBase(requestedGame)
      log(result.status === 'already_exists' ? 'info' : 'success', `${requestedGame}: ${result.message}`)
    } catch (error) {
      log('error', `${requestedGame}: ${error instanceof Error ? error.message : 'Knowledge-base acquisition failed.'}`)
    } finally {
      buildInFlight.current = false
      setIsBuilding(false)
    }
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <a className="brand" href="./" aria-label="Project YCG home">
          <span className="brand-mark"><span /><span /><span /></span>
          <span>Project <strong>YCG</strong></span>
        </a>
        <div className="header-divider" />
        <span className="header-label">Gameplay chapter studio</span>
        <span className="local-badge"><span className="status-dot" />Local workspace</span>
      </header>

      <main className="workspace">
        <section className="intro" aria-labelledby="page-title">
          <div>
            <p className="eyebrow">YOUR GAMEPLAY, ORGANIZED</p>
            <h1 id="page-title">Every great moment.<br className="mobile-break" /> A chapter.</h1>
            <p className="intro-description">Build game knowledge. Turn your recordings into meaningful chapters.</p>
          </div>
          <span className="preview-badge">Interface preview</span>
        </section>

        <section className="setup-panel" aria-labelledby="setup-title">
          <div className="section-heading">
            <div className="heading-group"><span className="step-number">01</span><h2 id="setup-title">Set up your workspace</h2></div>
            <span className="section-caption">Start with a game and an action</span>
          </div>
          <form onSubmit={startKnowledge}>
            <div className="form-fields">
              <div className="field">
                <label htmlFor="game-name">Game name</label>
                <input id="game-name" name="gameName" placeholder="e.g. Mortal Shell" value={gameName} onChange={event => setGameName(event.target.value)} required maxLength={200} autoComplete="off" aria-describedby="game-hint" disabled={isBuilding} />
                <p id="game-hint" className="field-hint">The game you want to work with.</p>
              </div>
              <div className="field">
                <label htmlFor="action">Action</label>
                <div className="select-wrapper">
                  <select id="action" value={action} onChange={event => selectAction(event.target.value as Action)} disabled={isBuilding}>
                    <option value="knowledge">Build Knowledge Base</option>
                    <option value="chapters">Build Chapters</option>
                  </select>
                  <svg viewBox="0 0 16 16" width="16" height="16" fill="none" aria-hidden="true"><path d="m4 6 4 4 4-4" stroke="currentColor" strokeWidth="1.5" /></svg>
                </div>
                <p className="field-hint">Choose the next step for your game.</p>
              </div>
            </div>

            {action === 'knowledge' ? (
              <div className="action-content">
                <div className="knowledge-overview">
                  <div className="action-icon"><Icon name="book" width="27" height="27" /></div>
                  <div className="action-copy">
                    <h3>Great chapters start with game knowledge.</h3>
                    <p>Prepare the boss names and reference information that will help identify encounters in your gameplay.</p>
                    <div className="knowledge-tags"><span>Bosses & encounters</span><span>Game context</span></div>
                  </div>
                  <div className="knowledge-art" aria-hidden="true">
                    <div className="art-line" /><div className="art-node node-top" /><div className="art-node node-bottom" />
                    <div className="art-card"><Icon name="book" width="24" height="24" /><i /><i /><i /></div>
                  </div>
                </div>
                <div className="action-footer">
                  <p role="status"><span className="status-dot muted" />{isBuilding ? 'Waiting for LM Studio…' : 'Results are validated and printed in the backend console.'}</p>
                  <button className="primary-button" type="submit" disabled={!gameName.trim() || isBuilding}><Icon name="play" width="16" height="16" />{isBuilding ? 'Working…' : 'Start'}</button>
                </div>
              </div>
            ) : (
              <div className="action-content video-content">
                <div className="video-heading"><div><h3>Gameplay videos</h3><p>Add one recording or a complete playthrough.</p></div><span className="count-badge">{videos.length} selected</span></div>
                <div
                  className={`drop-zone${dragging ? ' is-dragging' : ''}`}
                  onDragEnter={event => { event.preventDefault(); if (event.dataTransfer.types.includes('Files')) { dragDepth.current++; setDragging(true) } }}
                  onDragOver={event => { event.preventDefault(); event.dataTransfer.dropEffect = 'copy' }}
                  onDragLeave={event => { event.preventDefault(); dragDepth.current = Math.max(0, dragDepth.current - 1); if (dragDepth.current === 0) setDragging(false) }}
                  onDrop={onDrop}
                >
                  <span className="upload-icon"><Icon name="upload" width="26" height="26" /></span>
                  <h4>{dragging ? 'Drop your videos here' : 'Drag & drop your gameplay here'}</h4>
                  <p>or <label className="browse-button">browse files<input type="file" multiple accept={videoAccept} aria-label="Browse video files" onChange={event => { if (event.target.files) addVideos(event.target.files); event.target.value = '' }} /></label> to choose your recordings</p>
                  <span className="format-hint">MP4, MKV, MOV, WEBM and other video formats</span>
                </div>
                {videos.length > 0 && (
                  <ul className="video-list" aria-label="Selected videos">
                    {videos.map(({ id, file }) => (
                      <li key={id}>
                        <span className="file-icon"><Icon name="video" /></span>
                        <div className="file-details"><span className="file-name" title={file.name}>{file.name}</span><span>{formatSize(file.size)}</span></div>
                        <span className="file-status">Selected</span>
                        <button className="icon-button" type="button" aria-label={`Remove ${file.name}`} onClick={() => { setVideos(previous => previous.filter(video => video.id !== id)); log('info', `Removed "${file.name}".`) }}><Icon name="close" width="16" height="16" /></button>
                      </li>
                    ))}
                  </ul>
                )}
                <div className="action-footer video-footer">
                  <p className="video-note"><span className="status-dot muted" />Files stay on your device. Processing is not connected yet.</p>
                  <button className="primary-button" type="button" disabled={!gameName.trim() || videos.length === 0}><Icon name="play" width="16" height="16" />Process</button>
                </div>
              </div>
            )}
          </form>
        </section>

        <section className="activity-panel" aria-labelledby="activity-title">
          <div className="section-heading activity-heading">
            <div className="heading-group"><Icon name="terminal" width="18" height="18" /><h2 id="activity-title">Activity log</h2><span className="log-count">{logs.length}</span></div>
            <button className="text-button" type="button" disabled={logs.length === 0} onClick={() => { setLogs([]); followLogs.current = true }}>Clear log</button>
          </div>
          <div className="log-panel" ref={logPanel} role="log" aria-label="Workspace activity" aria-live="polite" aria-relevant="additions" tabIndex={0} onScroll={event => { const panel = event.currentTarget; followLogs.current = panel.scrollHeight - panel.scrollTop - panel.clientHeight < 32 }}>
            {logs.length === 0 ? <p className="log-empty">No activity yet. New updates will appear here.</p> : logs.map(entry => (
              <div className="log-entry" key={entry.id}>
                <time dateTime={entry.timestamp.toISOString()}>{entry.timestamp.toLocaleTimeString('en-GB', { hour12: false })}</time>
                <span className={`log-level ${entry.level}`}>{entry.level}</span>
                <span className="log-message">{entry.message}</span>
              </div>
            ))}
          </div>
          <div className="log-footer"><span><span className="status-dot muted" />{isBuilding ? 'Knowledge acquisition in progress' : 'No active requests'}</span><span>Session activity · Latest {maxLogEntries} entries</span></div>
        </section>
      </main>
      <footer className="app-footer"><span>PROJECT YCG</span><span>Built around your gameplay.</span></footer>
    </div>
  )
}
