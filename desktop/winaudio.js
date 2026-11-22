// Volume des autres logiciels (jeu, Deezer, Discord…) sous Windows, comme le mélangeur de volume du système.
// Un seul processus PowerShell reste ouvert ; il parle à l'API audio de Windows (Core Audio) via un petit code C#.
const { spawn } = require('child_process');
const fs = require('fs'), os = require('os'), path = require('path');

const CS = String.raw`
using System; using System.Collections.Generic; using System.Diagnostics; using System.Runtime.InteropServices; using System.Text;
namespace CtvAudio {
  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCo {}
  [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceEnumerator { int NotImpl1(); [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice dev); }
  [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDevice { [PreserveSig] int Activate(ref Guid iid, int ctx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object o); }
  [Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionManager2 { int NotImpl1(); int NotImpl2(); [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator e); }
  [Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionEnumerator { [PreserveSig] int GetCount(out int n); [PreserveSig] int GetSession(int i, out IAudioSessionControl2 s); }
  [Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionControl2 {
    [PreserveSig] int GetState(out int st);
    [PreserveSig] int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string n);
    int SetDisplayName(); int GetIconPath(); int SetIconPath(); int GetGroupingParam(); int SetGroupingParam(); int RegisterNotif(); int UnregisterNotif();
    [PreserveSig] int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
    [PreserveSig] int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
    [PreserveSig] int GetProcessId(out uint pid);
    [PreserveSig] int IsSystemSoundsSession();
  }
  [Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface ISimpleAudioVolume {
    [PreserveSig] int SetMasterVolume(float v, ref Guid ctx); [PreserveSig] int GetMasterVolume(out float v);
    [PreserveSig] int SetMute(bool m, ref Guid ctx); [PreserveSig] int GetMute(out bool m);
  }
  public static class Mixer {
    static IEnumerable<object[]> Sessions() {
      var en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorCo()); IMMDevice dev; en.GetDefaultAudioEndpoint(0, 1, out dev); if (dev == null) yield break;
      var iid = typeof(IAudioSessionManager2).GUID; object o; dev.Activate(ref iid, 23, IntPtr.Zero, out o);
      var mgr = (IAudioSessionManager2)o; IAudioSessionEnumerator se; mgr.GetSessionEnumerator(out se); int n; se.GetCount(out n);
      for (int i = 0; i < n; i++) { IAudioSessionControl2 c; se.GetSession(i, out c); uint pid; c.GetProcessId(out pid); int st; c.GetState(out st);
        if (c.IsSystemSoundsSession() == 0) continue; yield return new object[] { c, pid, st }; }
    }
    static string Esc(string s) { return (s ?? "").Replace("\\", "\\\\").Replace("\"", "\\\""); }
    public static string List(string self) {
      var sb = new StringBuilder("["); var seen = new HashSet<string>();
      foreach (var x in Sessions()) {
        var c = (IAudioSessionControl2)x[0]; uint pid = (uint)x[1]; int st = (int)x[2]; if (pid == 0 || st == 2) continue;
        string name = ""; string title = "";
        try { var p = Process.GetProcessById((int)pid); name = p.ProcessName; title = p.MainWindowTitle; if (string.Equals(name, self, StringComparison.OrdinalIgnoreCase)) continue; } catch { continue; }
        var v = (ISimpleAudioVolume)c; float vol; bool mute; v.GetMasterVolume(out vol); v.GetMute(out mute);
        if (!seen.Add(name.ToLowerInvariant())) continue;
        if (sb.Length > 1) sb.Append(",");
        sb.Append("{\"pid\":" + pid + ",\"name\":\"" + Esc(name) + "\",\"title\":\"" + Esc(title) + "\",\"vol\":" + vol.ToString(System.Globalization.CultureInfo.InvariantCulture) + ",\"mute\":" + (mute ? "true" : "false") + ",\"active\":" + (st == 1 ? "true" : "false") + "}");
      }
      return sb.Append("]").ToString();
    }
    // tous les flux du même logiciel (même nom de processus) suivent le même réglage
    public static void Set(string name, float vol, int mute) {
      var g = Guid.Empty;
      foreach (var x in Sessions()) {
        uint pid = (uint)x[1]; string n = ""; try { n = Process.GetProcessById((int)pid).ProcessName; } catch { continue; }
        if (!string.Equals(n, name, StringComparison.OrdinalIgnoreCase)) continue;
        var v = (ISimpleAudioVolume)x[0]; if (vol >= 0) v.SetMasterVolume(Math.Max(0f, Math.Min(1f, vol)), ref g); if (mute >= 0) v.SetMute(mute == 1, ref g);
      }
    }
  }
}`;

const PS = `
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
Add-Type -TypeDefinition @'
${CS}
'@
[Console]::Out.WriteLine('{"ready":true}'); [Console]::Out.Flush()
while ($true) {
  $l = [Console]::In.ReadLine(); if ($l -eq $null) { break }
  try {
    $c = $l | ConvertFrom-Json
    if ($c.op -eq 'list') { $r = [CtvAudio.Mixer]::List([string]$c.self) }
    elseif ($c.op -eq 'set') { [CtvAudio.Mixer]::Set([string]$c.name, [float]$c.vol, [int]$c.mute); $r = '{"ok":true}' }
    else { $r = '{"err":"op"}' }
  } catch { $r = '{"err":' + (ConvertTo-Json ([string]$_.Exception.Message)) + '}' }
  [Console]::Out.WriteLine('{"id":' + $c.id + ',"r":' + $r + '}'); [Console]::Out.Flush()
}
`;

let ps = null, buf = '', seq = 0, waiting = new Map(), readyP = null, broken = '';

function start() {
  if (process.platform !== 'win32') { broken = 'Disponible seulement sous Windows.'; return Promise.reject(new Error(broken)); }
  if (readyP) return readyP;
  readyP = new Promise((ok, ko) => {
    const file = path.join(os.tmpdir(), 'capitoltv-mixer.ps1');
    try { fs.writeFileSync(file, '\ufeff' + PS.replace(/\r?\n/g, '\r\n'), 'utf8'); } catch (e) { ko(e); return; }
    ps = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', file], { windowsHide: true });
    const t = setTimeout(() => ko(new Error('Le mélangeur Windows ne répond pas.' + (broken ? ' ' + broken : ''))), 25000);
    ps.stdout.on('data', d => {
      buf += d.toString('utf8'); let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1); if (!line) continue;
        let m; try { m = JSON.parse(line); } catch (e) { continue; }
        if (m.ready) { clearTimeout(t); ok(); continue; }
        const w = waiting.get(m.id); if (w) { waiting.delete(m.id); m.r && m.r.err ? w.ko(new Error(m.r.err)) : w.ok(m.r); }
      }
    });
    ps.stderr.on('data', d => { broken = d.toString('utf8').slice(0, 300); });
    ps.on('exit', () => { ps = null; readyP = null; for (const w of waiting.values()) w.ko(new Error('Mélangeur arrêté')); waiting.clear(); });
  });
  readyP.catch(() => { readyP = null; });
  return readyP;
}
function call(op, args) {
  return start().then(() => new Promise((ok, ko) => {
    const id = ++seq; waiting.set(id, { ok, ko });
    setTimeout(() => { if (waiting.has(id)) { waiting.delete(id); ko(new Error('Délai dépassé')); } }, 8000);
    ps.stdin.write(JSON.stringify({ id, op, ...args }) + '\r\n');
  }));
}
module.exports = {
  list: () => call('list', { self: path.basename(process.execPath, path.extname(process.execPath)) }),
  set: (name, vol, mute) => call('set', { name: String(name || ''), vol: vol == null ? -1 : +vol, mute: mute == null ? -1 : (mute ? 1 : 0) }),
  stop: () => { try { ps && ps.kill(); } catch (e) {} }
};
