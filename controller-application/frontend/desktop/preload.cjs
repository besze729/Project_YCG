const { contextBridge, webUtils } = require('electron')

// Expose only the path of a user-selected File, never general filesystem access.
contextBridge.exposeInMainWorld('ycgDesktop', {
  getPathForFile: file => webUtils.getPathForFile(file),
})
