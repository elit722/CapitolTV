// Capitol TV — application de bureau (Windows / macOS / Linux).
// Une fenêtre qui ouvre le site, avec : connexion gardée, son et vidéo sans clic, notifications
// système, et le « double écran » de la régie ouvert directement sur le 2e moniteur.
const { app, BrowserWindow, screen, shell, ipcMain, session, Menu } = require('electron');
const path = require('path');
const fs = require('fs');
const winaudio = require('./winaudio');

const CFG = (() => { try { return JSON.parse(fs.readFileSync(path.join(__dirname, 'config.json'), 'utf8')); } catch (e) { return {}; } })();
const statePath = () => path.join(app.getPath('userData'), 'fenetres.json');
const loadState = () => { try { return JSON.parse(fs.readFileSync(statePath(), 'utf8')); } catch (e) { return {}; } };
const saveState = s => { try { fs.writeFileSync(statePath(), JSON.stringify(s)); } catch (e) {} };
let state = {};
let main = null, second = null;

// adresse du site : celle du dernier lancement (suit la bascule vers le site de secours), sinon config.json
const siteUrl = () => state.url || CFG.url || 'https://capitol-tv.refugeemeraudien-direction.workers.dev';
const siteOrigin = () => { try { return new URL(siteUrl()).origin; } catch (e) { return ''; } };
// pages qui peuvent s'ouvrir dans l'application (connexion Discord / Twitch comprises)
const inApp = u => { try { const x = new URL(u); return x.origin === siteOrigin() || /(^|\.)discord\.com$/.test(x.hostname) || x.hostname === 'id.twitch.tv' || /\.workers\.dev$/.test(x.hostname); } catch (e) { return false; } };

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('disable-background-timer-throttling');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
if (process.platform === 'win32') app.setAppUserModelId('fr.capitoltv.app');

if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => { if (main) { if (main.isMinimized()) main.restore(); main.focus(); } });

function webPrefs() {
  return { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true, backgroundThrottling: false, spellcheck: false };
}

function guard(win) {
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/#ecran2/i.test(url)) { openSecond(url); return { action: 'deny' }; }
    if (inApp(url) && /twitchbot\/start/.test(url)) { win.loadURL(url); return { action: 'deny' }; }
    shell.openExternal(url); return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => { if (!inApp(url)) { e.preventDefault(); shell.openExternal(url); } });
  // le site a déménagé (bascule vers le site de secours) : on retient la nouvelle adresse
  win.webContents.on('did-navigate', (e, url) => {
    try { const x = new URL(url); if (/\.workers\.dev$|capitol/i.test(x.hostname) && x.origin !== siteOrigin() && !/discord|twitch/.test(x.hostname)) { state.url = x.origin; saveState(state); } } catch (e2) {}
  });
  win.webContents.on('before-input-event', (e, inp) => {
    if (inp.type !== 'keyDown') return;
    if (inp.key === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    if (inp.key === 'F5' || (inp.control && inp.key.toLowerCase() === 'r')) { win.webContents.reload(); e.preventDefault(); }
    if (inp.control && inp.shift && inp.key.toLowerCase() === 'i') { win.webContents.toggleDevTools(); e.preventDefault(); }
    if (inp.control && (inp.key === '+' || inp.key === '=')) { win.webContents.setZoomLevel(win.webContents.getZoomLevel() + .5); e.preventDefault(); }
    if (inp.control && inp.key === '-') { win.webContents.setZoomLevel(win.webContents.getZoomLevel() - .5); e.preventDefault(); }
    if (inp.control && inp.key === '0') { win.webContents.setZoomLevel(0); e.preventDefault(); }
  });
}

function openSecond(url) {
  if (second && !second.isDestroyed()) { second.loadURL(url); second.focus(); return; }
  const displays = screen.getAllDisplays(), prim = screen.getPrimaryDisplay();
  const where = main ? screen.getDisplayMatching(main.getBounds()) : prim;
  const other = displays.find(d => d.id !== where.id) || where;
  second = new BrowserWindow({ x: other.bounds.x + 40, y: other.bounds.y + 40, width: Math.min(1600, other.workArea.width), height: Math.min(900, other.workArea.height), backgroundColor: '#0b0b0d', title: 'Capitol TV — 2e écran', icon: path.join(__dirname, 'icon.png'), autoHideMenuBar: true, webPreferences: webPrefs() });
  guard(second);
  second.loadURL(url);
  if (other.id !== where.id) second.once('ready-to-show', () => second.setFullScreen(true));
  second.on('closed', () => { second = null; });
}

function createMain() {
  const b = state.bounds || {};
  main = new BrowserWindow({ x: b.x, y: b.y, width: b.width || 1500, height: b.height || 900, minWidth: 900, minHeight: 600, backgroundColor: '#0b0b0d', title: 'Capitol TV', icon: path.join(__dirname, 'icon.png'), autoHideMenuBar: true, show: false, webPreferences: webPrefs() });
  if (state.max) main.maximize();
  main.once('ready-to-show', () => main.show());
  guard(main);
  main.loadURL(siteUrl());
  main.webContents.on('did-fail-load', (e, code, desc, url, isMain) => {
    if (!isMain || code === -3) return;
    main.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(`<body style="background:#0b0b0d;color:#ddd;font:15px system-ui;display:grid;place-items:center;height:100vh;margin:0"><div style="text-align:center"><h2 style="letter-spacing:.2em;color:#d6b261">CAPITOL TV</h2><p>Connexion impossible (${desc}).</p><p>Vérifie ta connexion Internet.</p><button onclick="location.href='${siteUrl()}'" style="padding:10px 20px;border-radius:8px;border:1px solid #d6b261;background:#1a1712;color:#f6e2a6;cursor:pointer">Réessayer</button></div></body>`));
  });
  const keep = () => { if (!main || main.isDestroyed()) return; state.max = main.isMaximized(); if (!state.max && !main.isFullScreen()) state.bounds = main.getBounds(); saveState(state); };
  main.on('resize', keep); main.on('move', keep); main.on('close', keep);
  main.on('focus', () => main.flashFrame(false));
  main.on('closed', () => { main = null; if (second && !second.isDestroyed()) second.close(); });
}

app.whenReady().then(() => {
  state = loadState();
  Menu.setApplicationMenu(null);
  // micro (interphone), notifications, plein écran : autorisés pour le site seulement
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb, details) => {
    const ok = ['media', 'notifications', 'fullscreen', 'clipboard-sanitized-write', 'clipboard-read', 'display-capture'].includes(perm);
    cb(ok && inApp(details.requestingUrl || wc.getURL()));
  });
  session.defaultSession.setPermissionCheckHandler((wc, perm, origin) => ['media', 'notifications', 'fullscreen', 'clipboard-sanitized-write'].includes(perm) && inApp(origin || ''));
  ipcMain.on('ctv:attention', e => { const w = BrowserWindow.fromWebContents(e.sender); if (w && !w.isFocused()) w.flashFrame(true); });
  ipcMain.on('ctv:screen2', (e, u) => { if (inApp(u)) openSecond(u); });
  ipcMain.handle('ctv:version', () => app.getVersion());
  // volumes des autres logiciels (mélangeur Windows)
  ipcMain.handle('ctv:audList', () => winaudio.list().catch(e => ({ err: e.message })));
  ipcMain.handle('ctv:audSet', (e, name, vol, mute) => winaudio.set(name, vol, mute).catch(er => ({ err: er.message })));
  createMain();
  app.on('activate', () => { if (!main) createMain(); });
});
app.on('will-quit', () => winaudio.stop());
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
