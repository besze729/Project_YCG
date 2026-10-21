import { spawn, execFileSync } from 'node:child_process'
import { mkdir, mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import net from 'node:net'
import assert from 'node:assert/strict'
import electronPath from 'electron'

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const software = path.resolve(frontend, '../..')
const cache = path.resolve(frontend, '../.cache')
await mkdir(cache, { recursive: true })
const work = await mkdtemp(path.join(cache, 'proxy-integration-'))
const children = []
const logs = []
const start = (command, args, env = {}) => {
  const child = spawn(command, args, { cwd: software, env: { ...process.env, ...env }, windowsHide: true })
  child.stdout.on('data', data => logs.push(data.toString()))
  child.stderr.on('data', data => logs.push(data.toString()))
  child.on('error', error => logs.push(String(error)))
  children.push(child)
  return child
}
const available = port => new Promise((resolve, reject) => {
  const server = net.createServer()
  server.once('error', reject)
  server.listen(port, '127.0.0.1', () => server.close(resolve))
})
const waitFor = async url => {
  for (let i = 0; i < 300; i++) {
    try { await fetch(url); return } catch { await new Promise(resolve => setTimeout(resolve, 100)) }
  }
  throw new Error('Service did not start: ' + url)
}
const hash = async file => createHash('sha256').update(await readFile(file)).digest('hex')
try {
  await available(18080); await available(18090)
  const sources = [path.join(work, 'árvíz game 1.mp4'), path.join(work, 'game 2.mp4')]
  for (const source of sources) execFileSync('ffmpeg', ['-v', 'error', '-f', 'lavfi', '-i',
    'testsrc2=size=320x180:rate=10:duration=4', '-c:v', 'libx264', source], { windowsHide: true })
  const originalHashes = await Promise.all(sources.map(hash))
  await writeFile(path.join(work, 'cpu.toml'), '[proxy]\nvideo_codec="libx264"\nencoder_preset="ultrafast"\n[progress]\ninterval_seconds=0.1\n')
  start(path.join(software, 'video-processing-service/.venv/Scripts/python.exe'), ['-m', 'video_processing_service.api'], {
    YCG_VIDEO_PORT: '18090', YCG_VIDEO_CONFIG: path.join(work, 'cpu.toml'),
  })
  start('java', ['-jar', path.join(software, 'controller-application/backend/target/controller-backend-0.0.1-SNAPSHOT.jar')], {
    SERVER_PORT: '18080', YCG_VIDEO_SERVICE_URL: 'http://127.0.0.1:18090', YCG_PROXY_OUTPUT_ROOT: path.join(work, 'jobs'),
  })
  await Promise.all([waitFor('http://127.0.0.1:18090/health'), waitFor('http://127.0.0.1:18080/api/chapters/jobs/not-a-job')])
  const electron = start(electronPath, [path.join(frontend, 'desktop/smoke.cjs')], {
    YCG_CONTROLLER_URL: 'http://127.0.0.1:18080', YCG_SMOKE_PROFILE: path.join(work, 'electron-profile'),
    YCG_SMOKE_VIDEOS: JSON.stringify(sources), YCG_SMOKE_SCREENSHOT: path.join(work, 'desktop.png'),
  })
  const code = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { electron.kill(); reject(new Error('Desktop smoke timed out')) }, 90000)
    electron.once('exit', code => { clearTimeout(timer); resolve(code) })
    electron.once('error', error => { clearTimeout(timer); reject(error) })
  })
  assert.equal(code, 0)
  const [id] = await readdir(path.join(work, 'jobs'))
  const job = JSON.parse(await readFile(path.join(work, 'jobs', id, 'job.json'), 'utf8'))
  assert.equal(job.state, 'completed')
  assert.equal(job.videos.length, 2)
  const events = (await readFile(path.join(work, 'jobs', id, 'events.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse)
  for (let i = 0; i < sources.length; i++) {
    assert.equal(job.videos[i].inputPath, sources[i])
    assert.equal(await hash(sources[i]), originalHashes[i])
    assert.ok(events.some(event => event.videoIndex === i && event.command?.includes(sources[i])))
    assert.ok(events.some(event => event.videoIndex === i && event.state === 'running' && event.fps != null))
    const info = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_name,height,duration', '-of', 'json', job.videos[i].outputPath], { windowsHide: true, encoding: 'utf8' }))
    assert.equal(info.streams[0].codec_name, 'h264')
    assert.equal(info.streams[0].height, 480)
    assert.equal(Number(info.streams[0].duration), 4)
  }
  assert.deepEqual((await readdir(path.join(work, 'jobs', id))).sort(), ['0001-proxy.mp4', '0002-proxy.mp4', 'events.jsonl', 'job.json'])
  console.log('LIVE_INTEGRATION_OK: original paths and hashes, two H.264/480p proxies, timestamps, live progress; no source copies')
  console.log('Artifacts: ' + work)
} catch (error) {
  console.error(logs.join(''))
  throw error
} finally {
  for (const child of children) if (child.exitCode === null) child.kill()
  await writeFile(path.join(work, 'services.log'), logs.join(''))
}
