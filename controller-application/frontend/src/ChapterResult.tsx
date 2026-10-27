import { useEffect, useState } from 'react'
import { getChapterResults } from './videoProcessingApi'
import type { ChapterResults } from './videoProcessingApi'
import './App.css'

export default function ChapterResult({ jobId }: { jobId: string }) {
  const [result, setResult] = useState<ChapterResults | null>(null)
  const [selected, setSelected] = useState(0)
  const [error, setError] = useState('')
  useEffect(() => {
    document.title = 'YouTube chapters — Project YCG'
    const controller = new AbortController()
    getChapterResults(jobId, controller.signal).then(setResult).catch(reason => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Could not load results.')
    })
    return () => controller.abort()
  }, [jobId])
  const video = result?.videos[selected]
  return (
    <main className="chapter-result">
      <header><p className="eyebrow">PROJECT YCG</p><h1>YouTube chapters</h1>
        {result && <p>{result.gameName}</p>}</header>
      {error ? <p role="alert" className="result-error">{error}</p> : !video ? <p role="status">Loading results…</p> : <>
        {result!.videos.length > 1 ? <div className="field">
          <label htmlFor="result-video">Video</label>
          <select id="result-video" value={selected} onChange={event => setSelected(Number(event.target.value))}>
            {result!.videos.map((item, index) => <option key={item.videoIndex} value={index}>{index + 1}. {item.videoName}</option>)}
          </select>
        </div> : <p className="result-video-name">{video.videoName}</p>}
        {video.warnings.length > 0 && <aside className="result-warnings" aria-label="YouTube chapter notes">
          {video.warnings.map(warning => <p key={warning}>{warning}</p>)}
        </aside>}
        <label htmlFor="chapter-text">Chapter list</label>
        <textarea id="chapter-text" value={video.text} readOnly spellCheck={false} wrap="off"
          aria-describedby="chapter-copy-hint" />
        <p id="chapter-copy-hint">Select the text and copy it into your YouTube description.</p>
      </>}
    </main>
  )
}
