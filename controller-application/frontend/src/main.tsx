import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import ChapterResult from './ChapterResult'

const resultJobId = new URLSearchParams(window.location.search).get('result')

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {resultJobId ? <ChapterResult jobId={resultJobId} /> : <App />}
  </StrictMode>,
)
