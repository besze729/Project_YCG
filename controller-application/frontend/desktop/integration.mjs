import { spawn, execFileSync } from 'node:child_process'
import { mkdir, mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import net from 'node:net'
import http from 'node:http'
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
let knowledgeCalls = 0
const visionRequests = []
const knowledgeServer = http.createServer(async (request, response) => {
  if (request.url === '/v1/chat/completions' && request.method === 'POST') {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    visionRequests.push(body)
    const indices = body.messages[1].content.filter(item => item.type === 'text').map(item => Number(item.text.split(': ')[1]))
    const content = JSON.stringify({ results: indices.map(imageIndex => ({ imageIndex, activeBossFight: true })) })
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { role: 'assistant', content } }] }))
    return
  }
  if (request.url !== '/api/v1/chat' || request.method !== 'POST') {
    response.writeHead(501).end(); return
  }
  for await (const chunk of request) { /* consume the real LM Studio client request */ }
  knowledgeCalls++
  const content = JSON.stringify({ bosses: [{ displayName: `Test boss ${knowledgeCalls}`, classification: null }], sources: [] })
  response.writeHead(200, { 'Content-Type': 'text/event-stream' })
  response.end(`event: chat.end\ndata: ${JSON.stringify({ type: 'chat.end', result: { output: [{ type: 'message', content }] } })}\n\n`)
})
try {
  await new Promise(resolve => knowledgeServer.listen(0, '127.0.0.1', resolve))
  await available(18080); await available(18090)
  const sources = [path.join(work, 'árvíz game 1.mp4'), path.join(work, 'game 2.mp4')]
  for (const source of sources) execFileSync('ffmpeg', ['-v', 'error', '-f', 'lavfi', '-i',
    'testsrc2=size=320x180:rate=10:duration=4', '-c:v', 'libx264', source], { windowsHide: true })
  const originalHashes = await Promise.all(sources.map(hash))
  const badSource = path.join(work, 'bad-video.mp4')
  await writeFile(badSource, 'not a video')
  await writeFile(path.join(work, 'cpu.toml'), '[proxy]\nacceleration="cpu"\nvideo_codec="libx264"\nencoder_preset="ultrafast"\n[progress]\ninterval_seconds=0.1\n')
  await writeFile(path.join(work, 'vision.toml'), `[vision]\nbase_url="http://127.0.0.1:${knowledgeServer.address().port}"\n`)
  start(path.join(software, 'video-processing-service/.venv/Scripts/python.exe'), ['-m', 'video_processing_service.api'], {
    YCG_VIDEO_PORT: '18090', YCG_VIDEO_CONFIG: path.join(work, 'cpu.toml'),
    YCG_VISION_CONFIG: path.join(work, 'vision.toml'), LM_STUDIO_API_TOKEN: '',
  })
  start('java', ['-jar', path.join(software, 'controller-application/backend/target/controller-backend-0.0.1-SNAPSHOT.jar')], {
    SERVER_PORT: '18080', YCG_VIDEO_SERVICE_URL: 'http://127.0.0.1:18090', YCG_PROXY_OUTPUT_ROOT: path.join(work, 'jobs'),
    YCG_KNOWLEDGE_BASE_OUTPUT_ROOT: path.join(work, 'knowledge-base'),
    LM_STUDIO_BASE_URL: `http://127.0.0.1:${knowledgeServer.address().port}`, LM_STUDIO_API_TOKEN: '',
  })
  await Promise.all([waitFor('http://127.0.0.1:18090/health'), waitFor('http://127.0.0.1:18080/api/chapters/jobs/not-a-job')])
  const electron = start(electronPath, [path.join(frontend, 'desktop/smoke.cjs')], {
    YCG_CONTROLLER_URL: 'http://127.0.0.1:18080', YCG_SMOKE_PROFILE: path.join(work, 'electron-profile'),
    YCG_SMOKE_VIDEOS: JSON.stringify(sources), YCG_SMOKE_BAD_VIDEO: badSource,
    YCG_SMOKE_SCREENSHOT: path.join(work, 'desktop.png'),
    YCG_SMOKE_KNOWLEDGE_PATH: path.join(work, 'knowledge-base', 'Smoke game.json'),
  })
  const code = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { electron.kill(); reject(new Error('Desktop smoke timed out')) }, 90000)
    electron.once('exit', code => { clearTimeout(timer); resolve(code) })
    electron.once('error', error => { clearTimeout(timer); reject(error) })
  })
  assert.equal(code, 0)
  assert.equal(knowledgeCalls, 2, 'Cancellation must not invoke AI; confirmed rebuild must invoke it once')
  assert.equal(visionRequests.length, 4)
  assert.deepEqual(visionRequests.map(request => request.messages[1].content.filter(item => item.type === 'image_url').length), [1, 3, 1, 3])
  for (const request of visionRequests) {
    assert.equal(request.model, 'qwen3-vl-8b-instruct')
    assert.ok(request.messages[0].content.includes('video game Smoke game.'))
    assert.ok(!request.messages[0].content.includes('<?GAME_TITLE>'))
    assert.equal(request.response_format.type, 'json_schema')
  }
  const knowledge = JSON.parse(await readFile(path.join(work, 'knowledge-base', 'Smoke game.json'), 'utf8'))
  assert.equal(knowledge.bosses[0].displayName, 'Test boss 2')
  const ids = await readdir(path.join(work, 'jobs'))
  assert.equal(ids.length, 2)
  const snapshots = await Promise.all(ids.map(async id => ({
    id, data: JSON.parse(await readFile(path.join(work, 'jobs', id, 'job.json'), 'utf8')),
  })))
  const completed = snapshots.find(item => item.data.state === 'completed')
  const failed = snapshots.find(item => item.data.state === 'failed')
  assert.ok(completed)
  assert.ok(failed)
  const { id } = completed
  const job = JSON.parse(await readFile(path.join(work, 'jobs', id, 'job.json'), 'utf8'))
  assert.equal(job.state, 'completed')
  assert.equal(job.videos.length, 2)
  assert.equal(job.samplingIntervalSeconds, 30)
  const events = (await readFile(path.join(work, 'jobs', id, 'events.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse)
  for (let i = 0; i < sources.length; i++) {
    assert.equal(job.videos[i].inputPath, sources[i])
    assert.equal(await hash(sources[i]), originalHashes[i])
    assert.ok(events.some(event => event.videoIndex === i && event.command?.includes(sources[i])))
    assert.ok(events.some(event => event.videoIndex === i && event.state === 'running' && event.fps != null))
    const info = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_name,width,height,duration', '-of', 'json', job.videos[i].outputPath], { windowsHide: true, encoding: 'utf8' }))
    assert.equal(info.streams[0].codec_name, 'h264')
    assert.equal(info.streams[0].width, 1280)
    assert.equal(info.streams[0].height, 720)
    assert.equal(Number(info.streams[0].duration), 4)
    const frameFiles = await readdir(job.videos[i].framesDirectory)
    assert.deepEqual(frameFiles, ['frame_000000_00-00-00.000.jpg'])
    const frame = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_name,width,height', '-of', 'json',
      path.join(job.videos[i].framesDirectory, frameFiles[0])], { windowsHide: true, encoding: 'utf8' }))
    assert.equal(frame.streams[0].codec_name, 'mjpeg')
    assert.equal(frame.streams[0].width, 1280)
    assert.equal(frame.streams[0].height, 720)
    const sampling = events.filter(event => event.videoIndex === i && event.operation === 'sample')
    assert.ok(sampling.some(event => event.command?.includes(job.videos[i].outputPath)))
    assert.ok(sampling.some(event => event.state === 'running' && event.total_frames === 1 && event.percent === null))
    assert.equal(sampling.at(-1).state, 'completed')
    assert.equal(sampling.at(-1).frame_count, 1)
    assert.equal(sampling.at(-1).outputDirectory, job.videos[i].framesDirectory)
    const detections = (await readFile(job.videos[i].bossDetectionsPath, 'utf8')).trim().split('\n').map(JSON.parse)
    assert.deepEqual(detections, [{ imageIndex: 0, frameFile: frameFiles[0], sourceTimestampMs: 0, activeBossFight: true }])
    const detectionEvents = events.filter(event => event.videoIndex === i && event.operation === 'boss-detection')
    assert.match(detectionEvents[0].promptSha256, /^[a-f0-9]{64}$/)
    assert.equal(detectionEvents.at(-1).state, 'completed')
    assert.equal(detectionEvents.at(-1).outputFile, job.videos[i].bossDetectionsPath)
    const refinement = JSON.parse(await readFile(path.join(job.videos[i].bossRefinementDirectory, 'boss-intervals.json'), 'utf8'))
    assert.deepEqual(refinement.intervals, [{ startMs: 0, startBoundary: null, startClipped: true,
      endMs: 4000, endBoundary: null, endClipped: true }])
    assert.equal(await readFile(path.join(job.videos[i].bossRefinementDirectory, 'youtube-chapters.txt'), 'utf8'), '00:00 BOSS_FIGHT\n')
    assert.ok(events.some(event => event.videoIndex === i && event.state === 'interval'
      && event.message.includes('Boss fight #1: 00:00:00.000 -> 00:00:04.000')))
  }
  assert.deepEqual(events.filter(event => event.state === 'completed').map(event => [event.videoIndex, event.operation]),
    [[0, 'proxy'], [0, 'sample'], [0, 'boss-detection'], [0, 'boss-refinement'],
      [1, 'proxy'], [1, 'sample'], [1, 'boss-detection'], [1, 'boss-refinement']])
  assert.equal(events.at(-1).state, 'job_completed')
  assert.deepEqual((await readdir(path.join(work, 'jobs', id))).sort(),
    ['0001-boss-detections.jsonl', '0001-boss-refinement', '0001-frames', '0001-proxy.mp4',
      '0002-boss-detections.jsonl', '0002-boss-refinement', '0002-frames', '0002-proxy.mp4', 'events.jsonl', 'job.json'])
  assert.equal(failed.data.videos[0].inputPath, badSource)
  const failureEvents = (await readFile(path.join(work, 'jobs', failed.id, 'events.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse)
  assert.equal(failureEvents.filter(event => event.state === 'failed').length, 1)
  assert.equal(failureEvents.at(-1).state, 'job_failed')
  console.log('LIVE_INTEGRATION_OK: chapter exports, result window, video selection, warnings, replay, refinement and failure reporting')
  console.log('Artifacts: ' + work)
} catch (error) {
  console.error(logs.join(''))
  throw error
} finally {
  knowledgeServer.close()
  for (const child of children) if (child.exitCode === null) child.kill()
  await writeFile(path.join(work, 'services.log'), logs.join(''))
}
