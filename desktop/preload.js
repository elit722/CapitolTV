// Pont minimal entre le site et l'application (aucun accès au système de fichiers).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('ctvApp', {
  desktop: true,
  // fait clignoter la fenêtre dans la barre des tâches (notification reçue)
  attention: () => ipcRenderer.send('ctv:attention'),
  // ouvre le 2e écran de la régie sur l'autre moniteur
  openScreen2: url => ipcRenderer.send('ctv:screen2', String(url || '')),
  version: () => ipcRenderer.invoke('ctv:version'),
  // lecteur Deezer intégré (compte Premium connecté dans l'appli) : titre + volume, ou null pour arrêter
  dz: {
    set: o => ipcRenderer.send('ctv:dz', o ? { id: String(o.id || ''), vol: +o.vol } : null),
    show: () => ipcRenderer.send('ctv:dzShow'),
    onState: cb => ipcRenderer.on('ctv:dzState', (e, s) => cb(s))
  },
  // volumes des autres logiciels du PC (jeu, Deezer, Discord…) : Windows seulement
  audio: { list: () => ipcRenderer.invoke('ctv:audList'), set: (name, vol, mute) => ipcRenderer.invoke('ctv:audSet', String(name || ''), vol, mute) }
});
