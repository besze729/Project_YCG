const { app, BrowserWindow, protocol, net } = require('electron')
const path = require('node:path')
const { pathToFileURL } = require('node:url')

protocol.registerSchemesAsPrivileged([
  { scheme: 'ycg', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } },
])

function registerProtocol() {
  const root = path.resolve(__dirname, '../dist')
  const controller = new URL(process.env.YCG_CONTROLLER_URL || 'http://127.0.0.1:8080')
  if (controller.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(controller.hostname)) {
    throw new Error('YCG_CONTROLLER_URL must point to a local HTTP controller.')
  }
  protocol.handle('ycg', async request => {
    const url = new URL(request.url)
    if (url.host !== 'app') return new Response('Not found', { status: 404 })
    if (url.pathname.startsWith('/api/')) {
      // Only small JSON requests and SSE events cross this bridge, never videos.
      const headers = new Headers()
      for (const key of ['content-type', 'accept', 'last-event-id']) {
        if (request.headers.has(key)) headers.set(key, request.headers.get(key))
      }
      headers.set('Origin', 'ycg://app')
      try {
        // Node fetch keeps this server-to-server hop independent of renderer CORS.
        return await fetch(new URL(`${url.pathname}${url.search}`, controller).href, {
          method: request.method, headers, redirect: 'error', signal: request.signal,
          body: ['GET', 'HEAD'].includes(request.method) ? undefined : await request.arrayBuffer(),
        })
      } catch {
        return Response.json({ code: 'controller_unavailable', message: 'Could not reach the local controller. Check that it is running.' }, { status: 503 })
      }
    }
    const target = path.resolve(root, '.' + decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname))
    if (!target.startsWith(root + path.sep)) return new Response('Not found', { status: 404 })
    const response = await net.fetch(pathToFileURL(target).href)
    const headers = new Headers(response.headers)
    headers.set('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; frame-src 'none'; base-uri 'self'")
    return new Response(response.body, { status: response.status, headers })
  })
}

function createWindow(options = {}) {
  const window = new BrowserWindow({
    width: 1280, height: 960, minWidth: 600, minHeight: 600,
    title: 'Project YCG', backgroundColor: '#0d1117', ...options,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true,
      nodeIntegration: false, sandbox: true },
  })
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  window.webContents.on('will-navigate', event => event.preventDefault())
  window.loadURL('ycg://app/').then(() => {
    window.show()
    window.focus()
    console.log('Project YCG desktop window is ready.')
  }).catch(error => {
    console.error('Project YCG desktop window could not load:', error)
  })
  return window
}

// Electron's main-process loader does not always set require.main to this file.
if (process.argv[1] && path.resolve(process.argv[1]) === __filename) {
  console.log('Project YCG desktop starting.')
  app.whenReady().then(() => {
    console.log('Electron is ready; creating desktop window.')
    registerProtocol(); createWindow()
  }).catch(error => {
    console.error('Project YCG desktop could not start:', error)
    app.exit(1)
  })
  app.on('window-all-closed', () => app.quit())
}

module.exports = { registerProtocol, createWindow }
