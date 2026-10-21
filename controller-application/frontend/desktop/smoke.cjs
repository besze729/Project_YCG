// Real Electron UI check, used by integration.mjs with isolated local services.
const { app } = require('electron')
const { registerProtocol, createWindow } = require('./main.cjs')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')

if (!process.env.YCG_SMOKE_PROFILE || !process.env.YCG_SMOKE_VIDEOS) throw new Error('Run through integration.mjs')
app.setPath('userData', process.env.YCG_SMOKE_PROFILE)
app.disableHardwareAcceleration()

app.whenReady().then(async () => {
  registerProtocol()
  const window = createWindow({ show: false })
  const wc = window.webContents
  const evaluate = code => wc.executeJavaScript(code)
  const waitFor = async code => {
    const until = Date.now() + 60000
    while (Date.now() < until) {
      if (await evaluate(code)) return
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    throw new Error('UI condition timed out: ' + code + '\n' + await evaluate('document.body.innerText'))
  }
  try {
    await waitFor('!!document.querySelector("#action")')
    await evaluate(`document.querySelector('#action').value='chapters'; document.querySelector('#action').dispatchEvent(new Event('change', {bubbles:true}))`)
    await waitFor(`!!document.querySelector('input[type=file]')`)
    assert.equal(await evaluate(`document.querySelector('.video-footer button').disabled`), true)
    wc.debugger.attach('1.3')
    await wc.debugger.sendCommand('DOM.enable')
    const doc = await wc.debugger.sendCommand('DOM.getDocument')
    const input = await wc.debugger.sendCommand('DOM.querySelector', { nodeId: doc.root.nodeId, selector: 'input[type=file]' })
    await evaluate(`document.querySelector('input[type=file]').addEventListener('change', e => {window.smokeFiles=Array.from(e.target.files)}, {capture:true})`)
    const videos = JSON.parse(process.env.YCG_SMOKE_VIDEOS)
    await wc.debugger.sendCommand('DOM.setFileInputFiles', { nodeId: input.nodeId, files: videos })
    await waitFor(`document.querySelectorAll('.video-list li').length===2`)
    assert.equal(await evaluate(`window.ycgDesktop.getPathForFile(window.smokeFiles[0])`), videos[0])
    assert.equal(await evaluate(`document.querySelector('.video-footer button').disabled`), true)
    await evaluate(`Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(document.querySelector('#game-name'),'Smoke game'); document.querySelector('#game-name').dispatchEvent(new Event('input',{bubbles:true}))`)
    await waitFor(`!document.querySelector('.video-footer button').disabled`)
    await evaluate(`document.querySelectorAll('.video-list button').forEach(button=>button.click())`)
    await waitFor(`document.querySelector('.video-footer button').disabled && !document.querySelector('.video-list')`)
    // The dropped File objects still originate from native disk selection.
    await evaluate(`{ const data=new DataTransfer(); window.smokeFiles.forEach(file=>data.items.add(file)); document.querySelector('.drop-zone').dispatchEvent(new DragEvent('drop',{bubbles:true,dataTransfer:data})); }`)
    await waitFor(`document.querySelectorAll('.video-list li').length===2`)
    await evaluate(`document.querySelector('.video-footer button').click()`)
    await waitFor(`document.body.innerText.includes('All proxy videos are ready.')`)
    const text = await evaluate('document.body.innerText')
    assert.equal((text.match(/FFmpeg command:/g) || []).length, 2)
    assert.ok(text.includes('proxy ready'))
    assert.equal(await evaluate(`document.querySelector('.video-footer button').disabled`), false)
    // Page reload must replay the persisted commands and terminal state.
    wc.reload()
    await waitFor(`document.body.innerText.includes('All proxy videos are ready.')`)
    assert.equal(((await evaluate('document.body.innerText')).match(/FFmpeg command:/g) || []).length, 2)
    if (process.env.YCG_SMOKE_SCREENSHOT) {
      await fs.writeFile(process.env.YCG_SMOKE_SCREENSHOT, (await wc.capturePage()).toPNG())
    }
    console.log('DESKTOP_SMOKE_OK: native paths, button validation, file removal/drop, two proxies, command log, reload replay')
    app.exit(0)
  } catch (error) {
    console.error(error)
    app.exit(1)
  }
})
