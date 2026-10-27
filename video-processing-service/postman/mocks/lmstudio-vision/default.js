// Contract: https://lmstudio.ai/docs/developer/openai-compat/structured-output
// This simulates the response protocol, not visual recognition accuracy.
const http = require('http')
http.createServer((req, res) => {
  const reply = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(body))
  }
  if (req.method !== 'POST' || req.url !== '/v1/chat/completions') {
    reply(501, { error: { message: 'Endpoint not modeled' } }); return
  }
  const body = req.body
  const content = body?.messages?.[1]?.content
  if (!Array.isArray(content) || !content.length || content.length % 2 !== 0 ||
      body.stream !== false || body.response_format?.type !== 'json_schema') {
    reply(400, { error: { message: 'Invalid image request' } }); return
  }
  const results = []
  for (let i = 0; i < content.length; i += 2) {
    const label = /^imageIndex: (\d+)$/.exec(content[i].text || '')
    if (!label || content[i + 1].type !== 'image_url' ||
        !content[i + 1].image_url?.url.startsWith('data:image/jpeg;base64,')) {
      reply(400, { error: { message: 'Expected indexed JPEG image' } }); return
    }
    results.push({ imageIndex: Number(label[1]), activeBossFight: false })
  }
  reply(200, { id: 'chatcmpl-vision-simulation', object: 'chat.completion', model: body.model,
    choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: JSON.stringify({ results }) } }] })
}).listen(process.env.PORT || 4500)
