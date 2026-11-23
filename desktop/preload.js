// Pont minimal entre le site et l'application (aucun accès au système de fichiers).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('ctvApp', {
  desktop: true,
  // fait clignoter la fenêtre dans la barre des tâches (notification reçue)
  attention: () => ipcRenderer.send('ctv:attention'),
  // ouvre le 2e écran de la régie sur l'autre moniteur
  openScreen2: url => ipcRenderer.send('ctv:screen2', String(url || '')),
  version: () => ipcRenderer.invoke('ctv:version'),
  // mise à jour automatique : état ({ state: idle|downloading|ready, version, pct }) et installation
  update: {
    state: () => ipcRenderer.invoke('ctv:updState'),
    on: cb => ipcRenderer.on('ctv:update', (e, u) => cb(u)),
    install: () => ipcRenderer.send('ctv:updInstall')
  },

  // volumes des autres logiciels du PC (jeu, Discord…) : Windows seulement
  audio: { list: () => ipcRenderer.invoke('ctv:audList'), set: (name, vol, mute) => ipcRenderer.invoke('ctv:audSet', String(name || ''), vol, mute) }
});
