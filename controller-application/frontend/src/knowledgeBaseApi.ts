type BuildResult = {
  status: 'completed' | 'already_exists'
  message: string
}

export async function buildKnowledgeBase(gameName: string): Promise<BuildResult> {
  let response: Response
  try {
    response = await fetch('/api/knowledge-base/build', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ gameName }),
    })
  } catch {
    throw new Error('Could not reach the controller backend. Check that it is running. The request may not have completed.')
  }

  let body: unknown
  try {
    body = await response.json()
  } catch {
    throw new Error(`The controller returned an unreadable response (HTTP ${response.status}). Check that the backend is running.`)
  }
  if (typeof body !== 'object' || body === null || !('message' in body) || typeof body.message !== 'string') {
    throw new Error('The controller returned an invalid response.')
  }
  if (!response.ok) throw new Error(body.message)
  if (!('status' in body) || (body.status !== 'completed' && body.status !== 'already_exists')) {
    throw new Error('The controller returned an unknown result.')
  }
  return { status: body.status, message: body.message }
}
