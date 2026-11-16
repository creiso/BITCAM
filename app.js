/* BITCAM — câmera, controles e orquestração do burst. O processamento fica em worker.js. */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var video = $('video'), frame = $('frame'), stage = $('stage');

  var MODES = {
    foto: { frames: 8, night: false, label: 'Foto' },
    noite: { frames: 16, night: true, label: 'Noite' }
  };
  var PIXEL_BUDGET = 100e6; // teto de memória do burst (quadros × pixels)

  var S = {
    stream: null, track: null, caps: {}, facing: 'environment', deviceId: null,
    mode: 'foto', bit: true, flash: false, busy: false, zoom: 1,
    photos: [], current: null, wake: null
  };

  // ---------- util ----------
  function status(t) { $('status').textContent = t || ''; }
  var hintTimer;
  function hint(t, ms) {
    var el = $('hint'); clearTimeout(hintTimer);
    if (!t) { el.hidden = true; return; }
    el.textContent = t; el.hidden = false;
    if (ms) hintTimer = setTimeout(function () { el.hidden = true; }, ms);
  }
  function apply(c) {
    if (!S.track || !S.track.applyConstraints) return Promise.resolve(false);
    return S.track.applyConstraints({ advanced: [c] }).then(function () { return true; }, function () { return false; });
  }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function fileName() {
    var d = new Date();
    return 'BITCAM_' + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) + '_' +
      pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds()) + '.jpg';
  }

  // ---------- câmera ----------
  function stopCamera() {
    if (S.stream) S.stream.getTracks().forEach(function (t) { t.stop(); });
    S.stream = null; S.track = null;
  }

  async function startCamera() {
    stopCamera();
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error('Este navegador não dá acesso à câmera. Abra em Chrome ou Safari, por HTTPS.');
    }
    // pede o máximo que o sensor entregar em 4:3
    var v = { width: { ideal: 4032 }, height: { ideal: 3024 }, frameRate: { ideal: 30 } };
    if (S.deviceId) v.deviceId = { exact: S.deviceId }; else v.facingMode = { ideal: S.facing };
    S.stream = await navigator.mediaDevices.getUserMedia({ video: v, audio: false });
    S.track = S.stream.getVideoTracks()[0];
    S.caps = (S.track.getCapabilities && S.track.getCapabilities()) || {};
    var st = S.track.getSettings ? S.track.getSettings() : {};
    if (st.facingMode) S.facing = st.facingMode;
    video.classList.toggle('mirror', S.facing === 'user');
    video.srcObject = S.stream;
    await video.play().catch(function () {});
    await new Promise(function (r) { video.videoWidth ? r() : video.addEventListener('loadedmetadata', r, { once: true }); });

    var c = {};
    ['focusMode', 'exposureMode', 'whiteBalanceMode'].forEach(function (k) {
      if (S.caps[k] && S.caps[k].indexOf('continuous') >= 0) c[k] = 'continuous';
    });
    if (Object.keys(c).length) apply(c);

    S.zoom = st.zoom || 1; S.flash = false;
    layout(); buildControls(); showInfo(); keepAwake();
  }

  function showInfo() {
    var n = S.bit ? burstCount() : 1;
    var mp = (video.videoWidth * video.videoHeight / 1e6).toFixed(1);
    status(video.videoWidth + '×' + video.videoHeight + ' · ' + mp + ' MP · ' + n + (n > 1 ? ' quadros' : ' quadro'));
    document.querySelectorAll('.modes button').forEach(function (b) {
      b.setAttribute('aria-selected', String(b.dataset.mode === S.mode));
    });
    $('btnBit').setAttribute('aria-pressed', String(S.bit));
  }

  function burstCount() {
    var px = video.videoWidth * video.videoHeight || 1;
    return Math.max(2, Math.min(MODES[S.mode].frames, Math.floor(PIXEL_BUDGET / px)));
  }

  // encaixa o quadro do vídeo no visor mantendo a proporção do sensor
  function layout() {
    var vw = video.videoWidth, vh = video.videoHeight;
    if (!vw) return;
    var sw = stage.clientWidth, sh = stage.clientHeight, k = Math.min(sw / vw, sh / vh);
    frame.style.width = Math.floor(vw * k) + 'px';
    frame.style.height = Math.floor(vh * k) + 'px';
  }

  async function keepAwake() {
    try { if (navigator.wakeLock && !S.wake) { S.wake = await navigator.wakeLock.request('screen'); S.wake.addEventListener('release', function () { S.wake = null; }); } } catch (e) {}
  }

  // ---------- controles de hardware (só aparecem se o aparelho expõe) ----------
  async function buildControls() {
    var caps = S.caps;
    $('btnFlash').hidden = !caps.torch;
    $('btnFlash').setAttribute('aria-pressed', 'false');

    var ev = caps.exposureCompensation;
    $('btnEv').hidden = !ev; $('evBox').hidden = true; $('btnEv').setAttribute('aria-pressed', 'false');
    if (ev) {
      var s = $('ev'); s.min = ev.min; s.max = ev.max; s.step = ev.step || 0.1; s.value = 0; $('evVal').textContent = '0';
    }

    var z = caps.zoom, zs = $('zooms'); zs.innerHTML = '';
    zs.hidden = !z;
    if (z) {
      var stops = [1, 2, 3, 5].filter(function (x) { return x >= z.min && x <= z.max; });
      if (z.min < 1) stops.unshift(z.min);
      stops.forEach(function (x) {
        var b = document.createElement('button');
        b.textContent = (Math.round(x * 10) / 10) + '×'; b.dataset.z = x;
        b.onclick = function () { setZoom(x); };
        zs.appendChild(b);
      });
      markZoom();
    }

    // lentes traseiras extras (ultrawide / tele), quando o navegador as lista
    var ls = $('lenses'); ls.innerHTML = ''; ls.hidden = true;
    try {
      var devs = (await navigator.mediaDevices.enumerateDevices()).filter(function (d) { return d.kind === 'videoinput'; });
      var backs = devs.filter(function (d) { return /back|rear|tras|environment/i.test(d.label); });
      if (S.facing !== 'user' && backs.length > 1) {
        var cur = S.track.getSettings().deviceId;
        backs.forEach(function (d, i) {
          var b = document.createElement('button');
          b.textContent = 'L' + (i + 1); b.title = d.label;
          b.setAttribute('aria-pressed', String(d.deviceId === cur));
          b.onclick = function () { S.deviceId = d.deviceId; restart(); };
          ls.appendChild(b);
        });
        ls.hidden = false;
      }
    } catch (e) {}
  }

  function markZoom() {
    var best = null, bd = 1e9;
    $('zooms').querySelectorAll('button').forEach(function (b) {
      var d = Math.abs(b.dataset.z - S.zoom); if (d < bd) { bd = d; best = b; }
      b.setAttribute('aria-pressed', 'false');
    });
    if (best && bd < 0.15) best.setAttribute('aria-pressed', 'true');
  }
  function setZoom(z) {
    var c = S.caps.zoom; if (!c) return;
    S.zoom = Math.max(c.min, Math.min(c.max, z));
    apply({ zoom: S.zoom }); markZoom();
  }

  async function restart() {
    try { await startCamera(); } catch (e) { gate(explain(e)); }
  }

  function explain(e) {
    if (e && (e.name === 'NotAllowedError' || e.name === 'SecurityError')) return 'Permissão da câmera negada. Libere a câmera para este site nas configurações do navegador e tente de novo.';
    if (e && (e.name === 'NotFoundError' || e.name === 'OverconstrainedError')) return 'Nenhuma câmera encontrada neste aparelho.';
    if (e && e.name === 'NotReadableError') return 'A câmera está em uso por outro app. Feche-o e tente de novo.';
    return (e && e.message) || 'Não foi possível abrir a câmera.';
  }
  function gate(msg) {
    $('gate').hidden = false;
    if (msg) $('gateMsg').textContent = msg;
  }

  // ---------- foco por toque e pinça ----------
  var pointers = new Map(), pinch = null, focusTimer;
  frame.addEventListener('pointerdown', function (e) {
    pointers.set(e.pointerId, e);
    if (pointers.size === 2) { var p = Array.from(pointers.values()); pinch = { d: dist(p[0], p[1]), z: S.zoom }; }
  });
  frame.addEventListener('pointermove', function (e) {
    if (!pointers.has(e.pointerId)) return;
    pointers.set(e.pointerId, e);
    if (pinch && pointers.size === 2) { var p = Array.from(pointers.values()); setZoom(pinch.z * dist(p[0], p[1]) / pinch.d); }
  });
  function up(e) {
    var was = pointers.size; pointers.delete(e.pointerId);
    if (was === 1 && !pinch && e.type === 'pointerup') tapFocus(e);
    if (pointers.size === 0) pinch = null;
  }
  frame.addEventListener('pointerup', up); frame.addEventListener('pointercancel', up);
  function dist(a, b) { return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) || 1; }

  function tapFocus(e) {
    var r = frame.getBoundingClientRect();
    var x = (e.clientX - r.left) / r.width, y = (e.clientY - r.top) / r.height;
    var f = $('focus'); f.hidden = true; void f.offsetWidth;
    f.style.left = x * 100 + '%'; f.style.top = y * 100 + '%'; f.hidden = false;
    clearTimeout(focusTimer);
    var c = { pointsOfInterest: [{ x: S.facing === 'user' ? 1 - x : x, y: y }] };
    var single = S.caps.focusMode && S.caps.focusMode.indexOf('single-shot') >= 0;
    if (single) c.focusMode = 'single-shot';
    apply(c);
    focusTimer = setTimeout(function () {
      f.hidden = true;
      if (single && S.caps.focusMode.indexOf('continuous') >= 0) apply({ focusMode: 'continuous' });
    }, 2500);
  }

  // ---------- burst ----------
  function nextFrame() {
    return new Promise(function (res) {
      var done = false, fin = function () { if (!done) { done = true; res(); } };
      if (video.requestVideoFrameCallback) video.requestVideoFrameCallback(fin);
      setTimeout(fin, video.requestVideoFrameCallback ? 250 : 40);
    });
  }

  var small = document.createElement('canvas'); small.width = 96; small.height = 72;
  var sctx = small.getContext('2d', { willReadFrequently: true });
  function sharpness(bmp) {
    sctx.drawImage(bmp, 0, 0, 96, 72);
    var d = sctx.getImageData(0, 0, 96, 72).data, s = 0;
    for (var y = 0; y < 71; y++) for (var x = 0; x < 95; x++) {
      var i = (y * 96 + x) * 4 + 1;
      s += Math.abs(d[i] - d[i + 4]) + Math.abs(d[i] - d[i + 384]);
    }
    return s;
  }

  var worker = null, waiting = null;
  function getWorker() {
    if (worker) return worker;
    worker = new Worker('worker.js');
    worker.onmessage = function (e) { var w = waiting; waiting = null; if (w) (e.data.type === 'error' ? w.rej(new Error(e.data.message)) : w.res(e.data)); };
    worker.onerror = function (e) { var w = waiting; waiting = null; worker = null; if (w) w.rej(new Error(e.message || 'Falha no processamento')); };
    return worker;
  }
  function call(msg, transfer) {
    return new Promise(function (res, rej) { waiting = { res: res, rej: rej }; getWorker().postMessage(msg, transfer || []); });
  }

  var work = document.createElement('canvas');
  var wctx = work.getContext('2d', { willReadFrequently: true });
  function toBlob(cv) { return new Promise(function (r) { cv.toBlob(r, 'image/jpeg', 0.95); }); }

  async function shoot() {
    if (S.busy || !S.track || !video.videoWidth) return;
    S.busy = true; $('shutter').classList.add('busy');
    var t0 = performance.now(), mode = MODES[S.mode], frames = [];
    try {
      if (S.flash && S.caps.torch) { await apply({ torch: true }); await new Promise(function (r) { setTimeout(r, 350); }); }
      var n = S.bit ? burstCount() : 1;
      if (navigator.vibrate) navigator.vibrate(8);
      $('shutterFx').classList.remove('on'); void $('shutterFx').offsetWidth; $('shutterFx').classList.add('on');
      if (n > 1 && mode.night) hint('Segure firme…');
      for (var i = 0; i < n; i++) {
        frames.push(await createImageBitmap(video));
        if (i < n - 1) await nextFrame();
      }
      if (S.flash && S.caps.torch) apply({ torch: false });
      hint(n > 1 ? 'Processando ' + n + ' quadros…' : null);

      var w = frames[0].width, h = frames[0].height;
      // referência = o mais nítido dos 3 primeiros (o toque no botão costuma tremer o 1º)
      var ri = 0, rs = -1;
      for (var k = 0; k < Math.min(3, n); k++) { var sh = sharpness(frames[k]); if (sh > rs) { rs = sh; ri = k; } }
      var ref = frames.splice(ri, 1)[0];
      work.width = w; work.height = h;
      var stats = { frames: 1, used: 1 };

      if (n > 1) {
        wctx.drawImage(ref, 0, 0);
        var d = wctx.getImageData(0, 0, w, h);
        await call({ type: 'ref', w: w, h: h, buf: d.data.buffer }, [d.data.buffer]);
        while (frames.length) {
          var f = frames.shift();
          wctx.drawImage(f, 0, 0); f.close();
          d = wctx.getImageData(0, 0, w, h);
          await call({ type: 'frame', buf: d.data.buffer }, [d.data.buffer]);
        }
        var res = await call({ type: 'finish', opts: { night: mode.night } });
        stats = res.stats;
        wctx.putImageData(new ImageData(new Uint8ClampedArray(res.buf), w, h), 0, 0);
      } else {
        wctx.drawImage(ref, 0, 0);
      }
      var blob = await toBlob(work);
      work.width = work.height = 1; // libera a memória do canvas

      var photo = {
        blob: blob, url: URL.createObjectURL(blob), name: fileName(), w: w, h: h, ref: n > 1 ? ref : null,
        info: (n > 1 ? stats.used + '/' + stats.frames + ' quadros fundidos' : '1 quadro, sem BIT') + ' · ' + w + '×' + h +
          ' · ' + ((performance.now() - t0) / 1000).toFixed(1) + ' s'
      };
      if (n === 1) ref.close();
      var prev = S.photos[0]; if (prev && prev.ref) { prev.ref.close(); prev.ref = null; }
      S.photos.unshift(photo);
      if (S.photos.length > 24) { var old = S.photos.pop(); URL.revokeObjectURL(old.url); }
      $('thumb').style.backgroundImage = 'url(' + photo.url + ')'; $('thumb').disabled = false;
      var saved = await download(photo);
      hint(saved ? (NATIVE ? 'Salva na galeria · ' : 'Salva · ') + photo.info : 'Não foi possível salvar a foto', saved ? 2200 : 4000);
    } catch (e) {
      frames.forEach(function (b) { b.close(); });
      if (S.caps.torch) apply({ torch: false });
      hint('Erro: ' + ((e && e.message) || e), 4000);
    } finally {
      S.busy = false; $('shutter').classList.remove('busy');
    }
  }

  // ---------- salvar / visualizar ----------
  var NATIVE = window.BitcamNative || null; // ponte do app Android

  // No Android nativo a foto vai direto para a galeria (DCIM/BITCAM).
  async function saveNative(p) {
    var u8 = new Uint8Array(await p.blob.arrayBuffer()), STEP = 3 * 128 * 1024;
    if (!NATIVE.begin(p.name)) return false;
    for (var o = 0; o < u8.length; o += STEP) {
      var part = u8.subarray(o, Math.min(u8.length, o + STEP)), bin = '';
      for (var i = 0; i < part.length; i += 8192) bin += String.fromCharCode.apply(null, part.subarray(i, i + 8192));
      if (!NATIVE.chunk(btoa(bin))) return false;
    }
    return NATIVE.end();
  }

  async function download(p) {
    if (NATIVE) return saveNative(p);
    var a = document.createElement('a'); a.href = p.url; a.download = p.name;
    document.body.appendChild(a); a.click(); a.remove();
    return true;
  }
  function canShare(p) {
    try { return !!(navigator.canShare && navigator.canShare({ files: [new File([p.blob], p.name, { type: 'image/jpeg' })] })); } catch (e) { return false; }
  }
  function openViewer(p) {
    if (!p) return;
    S.current = p;
    $('vImg').src = p.url; $('vInfo').textContent = p.info;
    $('vCompare').hidden = !p.ref; $('vShare').hidden = !canShare(p);
    $('viewer').hidden = false;
  }
  function showOrig(on) {
    var p = S.current, cv = $('vOrig');
    if (!p || !p.ref) return;
    if (on) { cv.width = p.w; cv.height = p.h; cv.getContext('2d').drawImage(p.ref, 0, 0); }
    cv.hidden = !on; $('vTag').hidden = !on;
    if (!on) cv.width = cv.height = 1;
  }

  // ---------- eventos ----------
  $('shutter').onclick = shoot;
  $('btnFlip').onclick = function () { S.deviceId = null; S.facing = S.facing === 'user' ? 'environment' : 'user'; restart(); };
  $('btnGrid').onclick = function () { var on = $('grid').hidden; $('grid').hidden = !on; this.setAttribute('aria-pressed', String(on)); };
  $('btnBit').onclick = function () { S.bit = !S.bit; showInfo(); hint(S.bit ? 'BIT ligado: burst + HDR' : 'BIT desligado: quadro direto do sensor', 1800); };
  $('btnFlash').onclick = function () { S.flash = !S.flash; this.setAttribute('aria-pressed', String(S.flash)); };
  $('btnEv').onclick = function () { var on = $('evBox').hidden; $('evBox').hidden = !on; this.setAttribute('aria-pressed', String(on)); };
  $('ev').oninput = function () { var v = +this.value; $('evVal').textContent = (v > 0 ? '+' : '') + v.toFixed(1); apply({ exposureCompensation: v }); };
  document.querySelectorAll('.modes button').forEach(function (b) { b.onclick = function () { S.mode = b.dataset.mode; showInfo(); }; });
  $('thumb').onclick = function () { openViewer(S.photos[0]); };
  $('vClose').onclick = function () { showOrig(false); $('viewer').hidden = true; };
  $('vSave').onclick = function () { if (S.current) download(S.current).then(function (ok) { $('vInfo').textContent = ok ? 'Salva' + (NATIVE ? ' na galeria' : '') : 'Não foi possível salvar'; }); };
  $('vShare').onclick = function () {
    var p = S.current; if (!p) return;
    navigator.share({ files: [new File([p.blob], p.name, { type: 'image/jpeg' })] }).catch(function () {});
  };
  var cmp = $('vCompare');
  cmp.addEventListener('pointerdown', function (e) { e.preventDefault(); showOrig(true); });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (t) { cmp.addEventListener(t, function () { showOrig(false); }); });
  cmp.addEventListener('contextmenu', function (e) { e.preventDefault(); });

  window.addEventListener('resize', layout);
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { if (!S.busy) stopCamera(); }
    else if (!S.stream && $('gate').hidden) restart();
  });
  window.addEventListener('keydown', function (e) { if ((e.code === 'Space' || e.code === 'Enter') && $('viewer').hidden && $('gate').hidden) { e.preventDefault(); shoot(); } });

  async function begin() {
    $('btnStart').disabled = true;
    try { await startCamera(); $('gate').hidden = true; }
    catch (e) { gate(explain(e)); }
    $('btnStart').disabled = false;
  }
  $('btnStart').onclick = begin;
  // se a permissão já foi dada, abre direto
  if (navigator.permissions && navigator.permissions.query) {
    navigator.permissions.query({ name: 'camera' }).then(function (p) { if (p.state === 'granted') begin(); }, function () {});
  }

  // ganchos usados pelo app Android (volume = disparo, botão voltar, permissão concedida)
  window.__bitcamShoot = function () { if ($('viewer').hidden && $('gate').hidden) shoot(); };
  window.__bitcamStart = function () { if (!S.stream) begin(); };
  window.__bitcamBack = function () { if ($('viewer').hidden) return false; $('vClose').onclick(); return true; };
  if (NATIVE) begin();

  if (!NATIVE && 'serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(function () {});
  window.__bitcam = S; // para depuração
})();
