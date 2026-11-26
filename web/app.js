/* TRC TELAS — compartilhamento de tela com WebRTC.
 * A sinalização usa o servidor público do PeerJS (0.peerjs.com) só como "correio":
 * ele repassa OFFER / ANSWER / CANDIDATE / LEAVE entre os aparelhos. O vídeo vai direto
 * de um aparelho para o outro. O app Android (Java) fala exatamente o mesmo protocolo.
 *
 *  quem assiste ──OFFER {t:'join'}──▶ quem compartilha
 *  quem assiste ◀─OFFER {t:'sdp'}──── quem compartilha   (oferta com o vídeo)
 *  quem assiste ──ANSWER {t:'sdp'}──▶ quem compartilha
 *  os dois      ◀─CANDIDATE {t:'ice'}─▶
 *  qualquer um  ──LEAVE {t:'bye'}───▶ encerra
 */
const PREFIX = 'trctelas-';                         // id de quem compartilha: trctelas-ABC123
const WEB_URL = 'https://trc-telas.vercel.app/';    // usado no link enviado pelo APK
const SIGNAL_URL = 'wss://0.peerjs.com:443/peerjs';
const ICE = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: ['turn:eu-0.turn.peerjs.com:3478', 'turn:us-0.turn.peerjs.com:3478'], username: 'peerjs', credential: 'peerjsp' }
  ]
};
const QUALITY = {
  eco:    { fps: 15, height: 720,  kbps: 1500 },
  normal: { fps: 30, height: 1080, kbps: 4000 },
  fluido: { fps: 60, height: 1080, kbps: 8000 }
};

const $ = id => document.getElementById(id);
const isAndroidApp = !!window.AndroidScreen;
const canWebShare = !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia);

/* ---------- sinalização ---------- */
class Sinal {
  constructor(id) { this.id = id; this.onmsg = null; this.onclose = null; }
  open() {
    return new Promise((resolve, reject) => {
      const token = Math.random().toString(36).slice(2);
      const url = `${SIGNAL_URL}?key=peerjs&id=${encodeURIComponent(this.id)}&token=${token}&version=1.5.4`;
      let opened = false;
      this.ws = new WebSocket(url);
      this.ws.onmessage = e => {
        let m; try { m = JSON.parse(e.data); } catch { return; }
        if (m.type === 'OPEN') {
          opened = true;
          this.hb = setInterval(() => { if (this.ws.readyState === 1) this.ws.send('{"type":"HEARTBEAT"}'); }, 5000);
          resolve();
        } else if (m.type === 'ID-TAKEN') { reject(new Error('id-taken')); }
        else if (m.type === 'ERROR') { reject(new Error((m.payload && m.payload.msg) || 'server-error')); }
        else if (this.onmsg) { this.onmsg(m); }
      };
      this.ws.onerror = () => { if (!opened) reject(new Error('network')); };
      this.ws.onclose = () => { clearInterval(this.hb); if (!opened) reject(new Error('network')); else if (this.onclose) this.onclose(); };
    });
  }
  send(type, dst, payload) {
    if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify({ type, dst, payload }));
  }
  close() { clearInterval(this.hb); this.onclose = null; try { this.ws.close(); } catch {} }
}

/* Candidatos ICE podem chegar antes da descrição remota: guardamos até ela estar pronta. */
function makePc(onCandidate) {
  const pc = new RTCPeerConnection(ICE);
  pc._remoteReady = null;
  pc._pending = [];
  pc.onicecandidate = e => { if (e.candidate) onCandidate(e.candidate.toJSON()); };
  return pc;
}
async function setRemote(pc, desc) {
  await pc.setRemoteDescription(desc);
  pc._remoteReady = true;
  for (const c of pc._pending) pc.addIceCandidate(c).catch(() => {});
  pc._pending = [];
}
function addCandidate(pc, c) {
  if (!pc || !c) return;
  if (pc._remoteReady) pc.addIceCandidate(c).catch(() => {});
  else pc._pending.push(c);
}

/* ---------- interface ---------- */
function makeCode() {
  const c = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; let s = '';
  for (let i = 0; i < 6; i++) s += c[Math.floor(Math.random() * c.length)];
  return s;
}
function show(id) { ['home', 'hostView', 'viewer'].forEach(v => $(v).classList.toggle('hidden', v !== id)); }
function shareLink(code) {
  const base = location.protocol.startsWith('http') && !location.host.includes('appassets') ? location.origin + location.pathname : WEB_URL;
  return base + '?sala=' + code;
}
function renderCode(code) {
  $('myCode').textContent = code;
  $('myCodeTiles').innerHTML = [...code].map(ch => '<span>' + ch + '</span>').join('');
}
function setWatchStatus(msg, isErr) {
  $('watchStatus').textContent = msg;
  $('watchStatus').classList.toggle('err', !!isErr);
}
function selectedQuality() {
  const el = document.querySelector('input[name="q"]:checked');
  return el ? el.value : 'normal';
}
function alertMsg(m) { setWatchStatus(''); alert(m); }

if (!isAndroidApp && !canWebShare) { $('btnShare').disabled = true; $('noShare').classList.remove('hidden'); }
if (isAndroidApp) {
  $('shareHint').textContent = 'O Android vai pedir permissão para gravar a tela.';
  $('appNote').classList.remove('hidden');
  $('preview').classList.add('hidden');
}

/* ================= QUEM COMPARTILHA (PC) ================= */
let host = null; // { sinal, stream, pcs: Map, quality, statsTimer }

async function startWebShare() {
  const q = QUALITY[selectedQuality()];
  let stream;
  try {
    $('btnShare').disabled = true;
    stream = await navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: { ideal: q.fps, max: q.fps }, height: { max: q.height } },
      audio: true
    });
  } catch (e) {
    $('btnShare').disabled = false;
    if (e && e.name === 'NotAllowedError') return; // cancelou a escolha da tela
    alertMsg('Não foi possível capturar a tela: ' + e.message); return;
  }
  const vt = stream.getVideoTracks()[0];
  vt.contentHint = q.fps >= 60 ? 'motion' : 'detail';

  const code = makeCode();
  renderCode(code);
  $('preview').srcObject = stream;
  show('hostView');
  $('hostStatus').textContent = 'Conectando ao servidor…';

  const sinal = new Sinal(PREFIX + code);
  host = { sinal, stream, pcs: new Map(), q, statsTimer: null };
  vt.onended = stopShare;

  sinal.onmsg = m => {
    const p = m.payload || {}, src = m.src, pc = host && host.pcs.get(src);
    if (m.type === 'OFFER' && p.t === 'join') addViewer(src);
    else if (m.type === 'ANSWER' && p.t === 'sdp' && pc) setRemote(pc, { type: 'answer', sdp: p.sdp }).catch(() => removeViewer(src));
    else if (m.type === 'CANDIDATE' && p.t === 'ice') addCandidate(pc, p.c);
    else if (m.type === 'LEAVE' || m.type === 'EXPIRE') removeViewer(src);
  };
  sinal.onclose = () => { if (host) $('hostStatus').textContent = 'Conexão com o servidor caiu. Pare e compartilhe de novo.'; };

  try { await sinal.open(); }
  catch (e) {
    stopShare();
    alertMsg(e.message === 'id-taken' ? 'Esse código já está em uso. Toque em Compartilhar tela de novo.' : 'Sem conexão com o servidor. Confira a internet.');
    return;
  }
  updateHost();
  host.statsTimer = setInterval(readHostFps, 1000);
}

async function addViewer(viewerId) {
  if (!host || host.pcs.has(viewerId)) return;
  const pc = makePc(c => host && host.sinal.send('CANDIDATE', viewerId, { t: 'ice', c }));
  host.pcs.set(viewerId, pc);
  host.stream.getTracks().forEach(t => pc.addTrack(t, host.stream));
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === 'failed' || pc.connectionState === 'closed') removeViewer(viewerId);
    else updateHost();
  };
  try {
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    tuneSenders(pc, host.q);
    host.sinal.send('OFFER', viewerId, { t: 'sdp', sdp: pc.localDescription.sdp });
  } catch { removeViewer(viewerId); }
  updateHost();
}

function tuneSenders(pc, q) {
  pc.getSenders().forEach(sender => {
    if (!sender.track || sender.track.kind !== 'video') return;
    const params = sender.getParameters();
    if (!params.encodings || !params.encodings.length) params.encodings = [{}];
    params.encodings[0].maxFramerate = q.fps;
    params.encodings[0].maxBitrate = q.kbps * 1000;
    if (q.fps >= 60) params.degradationPreference = 'maintain-framerate';
    sender.setParameters(params).catch(() => {});
  });
}

function removeViewer(viewerId) {
  if (!host) return;
  const pc = host.pcs.get(viewerId);
  if (pc) { try { pc.close(); } catch {} host.pcs.delete(viewerId); }
  updateHost();
}

let hostFps = '';
async function readHostFps() {
  if (!host) return;
  const pc = [...host.pcs.values()].find(p => p.connectionState === 'connected');
  if (!pc) { hostFps = ''; return; }
  try {
    const stats = await pc.getStats();
    stats.forEach(r => { if (r.type === 'outbound-rtp' && r.kind === 'video' && r.framesPerSecond) hostFps = Math.round(r.framesPerSecond) + ' fps'; });
    updateHost();
  } catch {}
}

function updateHost(nativeViewers) {
  if (!host && nativeViewers === undefined) return;
  const n = nativeViewers !== undefined ? nativeViewers
    : [...host.pcs.values()].filter(p => p.connectionState === 'connected').length;
  const who = n === 0 ? 'esperando alguém entrar' : n === 1 ? '1 pessoa assistindo' : n + ' pessoas assistindo';
  $('hostStatus').innerHTML = '<span class="dot"></span>Ao vivo, ' + who + (hostFps && n > 0 ? ' · ' + hostFps : '');
}

function stopShare() {
  if (isAndroidApp && nativeSharing) { nativeSharing = false; window.AndroidScreen.stop(); }
  if (host) {
    clearInterval(host.statsTimer);
    host.pcs.forEach((pc, id) => { host.sinal.send('LEAVE', id, { t: 'bye' }); try { pc.close(); } catch {} });
    host.sinal.close();
    host.stream.getTracks().forEach(t => t.stop());
    host = null;
  }
  hostFps = '';
  $('preview').srcObject = null;
  $('btnShare').disabled = false;
  show('home');
}

/* ================= QUEM COMPARTILHA (APP ANDROID) =================
 * A captura e o envio do vídeo são feitos em Java (WebRTC nativo, codificação por hardware).
 * A página só mostra o código e o status que o app manda por window.__nativeStatus. */
let nativeSharing = false;

function startAndroidShare() {
  const code = makeCode();
  const quality = selectedQuality();
  $('btnShare').disabled = true;
  window.__onAndroidCapture = ok => {
    if (!ok) { $('btnShare').disabled = false; return; }
    nativeSharing = true;
    renderCode(code);
    show('hostView');
    $('hostStatus').textContent = 'Conectando ao servidor…';
  };
  window.__nativeStatus = s => {
    if (!nativeSharing) return;
    if (s.state === 'live') updateHost(s.viewers || 0);
    else if (s.state === 'id-taken') { stopShare(); alertMsg('Esse código já está em uso. Toque em Compartilhar tela de novo.'); }
    else if (s.state === 'stopped') { nativeSharing = false; stopShare(); }
    else if (s.state === 'error') { $('hostStatus').textContent = s.msg || 'Sem conexão com o servidor. Confira a internet.'; }
  };
  window.AndroidScreen.start(PREFIX + code, quality);
}

$('btnShare').onclick = () => (isAndroidApp ? startAndroidShare() : startWebShare());
$('btnStop').onclick = stopShare;

$('btnCopy').onclick = async () => {
  const link = shareLink($('myCode').textContent);
  try { await navigator.clipboard.writeText(link); $('btnCopy').textContent = 'Link copiado'; }
  catch { prompt('Copie o link:', link); }
  setTimeout(() => $('btnCopy').textContent = 'Copiar link', 1500);
};
$('btnShareLink').onclick = () => {
  const code = $('myCode').textContent, link = shareLink(code);
  const text = 'Assista minha tela no TRC TELAS. Código: ' + code;
  if (isAndroidApp && window.AndroidScreen.shareText) window.AndroidScreen.shareText(text + '\n' + link);
  else if (navigator.share) navigator.share({ title: 'TRC TELAS', text, url: link }).catch(() => {});
  else $('btnCopy').click();
};

/* ================= QUEM ASSISTE ================= */
let view = null; // { sinal, pc, hostId, timer, fpsTimer }

async function watch(code) {
  code = code.trim().toUpperCase();
  if (code.length !== 6) { setWatchStatus('O código tem 6 letras ou números.', true); return; }
  setWatchStatus('Conectando…');
  $('btnWatch').disabled = true;

  const hostId = PREFIX + code;
  const sinal = new Sinal('v-' + Math.random().toString(36).slice(2, 12));
  view = { sinal, pc: null, hostId, timer: null, fpsTimer: null };
  view.timer = setTimeout(() => endView('Nenhuma tela com esse código. Confira as letras.', true), 15000);

  sinal.onmsg = async m => {
    if (!view || m.src !== hostId) return;
    const p = m.payload || {};
    if (m.type === 'OFFER' && p.t === 'sdp') {
      const pc = makePc(c => sinal.send('CANDIDATE', hostId, { t: 'ice', c }));
      view.pc = pc;
      pc.ontrack = e => {
        clearTimeout(view.timer);
        const v = $('remote');
        if (v.srcObject !== e.streams[0]) {
          v.srcObject = e.streams[0]; v.muted = true; v.play().catch(() => {});
          show('viewer');
          view.fpsTimer = setInterval(readViewerFps, 1000);
        }
        $('btnMute').classList.toggle('hidden', e.streams[0].getAudioTracks().length === 0);
      };
      pc.onconnectionstatechange = () => {
        if (pc.connectionState === 'failed') endView('Não consegui conectar com essa tela. Tente em outra rede (Wi-Fi ou 4G).', true);
      };
      try {
        await setRemote(pc, { type: 'offer', sdp: p.sdp });
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        sinal.send('ANSWER', hostId, { t: 'sdp', sdp: pc.localDescription.sdp });
      } catch { endView('Não consegui conectar com essa tela.', true); }
    } else if (m.type === 'CANDIDATE' && p.t === 'ice') {
      addCandidate(view.pc, p.c);
    } else if (m.type === 'LEAVE') {
      endView('A transmissão terminou.');
    } else if (m.type === 'EXPIRE') {
      endView('Nenhuma tela com esse código. Confira as letras.', true);
    }
  };

  try {
    await sinal.open();
    sinal.send('OFFER', hostId, { t: 'join' });
  } catch { endView('Sem conexão com o servidor. Confira a internet.', true); }
}

async function readViewerFps() {
  if (!view || !view.pc) return;
  try {
    const stats = await view.pc.getStats();
    stats.forEach(r => { if (r.type === 'inbound-rtp' && r.kind === 'video' && r.framesPerSecond !== undefined) $('fps').textContent = Math.round(r.framesPerSecond) + ' fps'; });
  } catch {}
}

function endView(msg = '', isErr = false) {
  if (view) {
    clearTimeout(view.timer); clearInterval(view.fpsTimer);
    view.sinal.send('LEAVE', view.hostId, { t: 'bye' });
    if (view.pc) try { view.pc.close(); } catch {}
    view.sinal.close();
    view = null;
  }
  $('remote').srcObject = null;
  $('fps').textContent = '';
  $('btnWatch').disabled = false;
  show('home');
  setWatchStatus(msg, isErr);
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
}

$('btnWatch').onclick = () => watch($('inCode').value);
function paintCells() {
  const v = $('inCode').value;
  [...$('cells').children].forEach((c, i) => { c.textContent = v[i] || ''; c.classList.toggle('next', i === Math.min(v.length, 5)); });
}
$('inCode').addEventListener('input', e => { e.target.value = e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6); paintCells(); });
$('inCode').addEventListener('keydown', e => { if (e.key === 'Enter') $('btnWatch').click(); });
$('btnLeave').onclick = () => endView();
$('btnMute').onclick = () => { const v = $('remote'); v.muted = !v.muted; $('btnMute').textContent = v.muted ? 'Ativar som' : 'Silenciar'; };
$('btnFull').onclick = () => {
  const el = $('viewer');
  if (document.fullscreenElement) document.exitFullscreen();
  else if (el.requestFullscreen) el.requestFullscreen().catch(() => {});
  else if ($('remote').webkitEnterFullscreen) $('remote').webkitEnterFullscreen(); // iPhone
};
window.addEventListener('pagehide', () => { if (view) endView(); if (host) stopShare(); });

// Link direto: ?sala=ABC123
const sala = new URLSearchParams(location.search).get('sala');
if (sala) { $('inCode').value = sala.toUpperCase().slice(0, 6); paintCells(); watch(sala); }
