/*
 * BITCAM — pipeline de fotografia computacional.
 *
 * 1. Burst: vários quadros do sensor são alinhados (translação global, busca
 *    em pirâmide) e fundidos com rejeição de movimento por pixel. Ruído cai
 *    ~√N sem borrar o que se mexeu.
 * 2. HDR local: camada-base com filtro guiado (preserva bordas), sombras
 *    levantadas e altas-luzes comprimidas sem achatar o contraste local.
 * 3. Acabamento: nitidez com coring, vibrance, leve calor e curva em S.
 *
 * Roda em Web Worker (worker.js) e em Node (test/pipeline.test.js).
 */
(function (root) {
  'use strict';

  var MAX_FRAMES = 32; // limite dos acumuladores Uint16/Uint8 com peso 4

  function luma(rgba, w, h) {
    var n = w * h, y = new Uint8Array(n);
    for (var i = 0, j = 0; i < n; i++, j += 4) {
      y[i] = (rgba[j] * 77 + rgba[j + 1] * 150 + rgba[j + 2] * 29) >> 8;
    }
    return y;
  }

  // Redução 4x por média de blocos.
  function down4(src, w, h) {
    var W = w >> 2, H = h >> 2, out = new Uint8Array(W * H);
    for (var Y = 0; Y < H; Y++) {
      for (var X = 0; X < W; X++) {
        var s = 0, p = (Y * 4) * w + X * 4;
        for (var k = 0; k < 4; k++, p += w) s += src[p] + src[p + 1] + src[p + 2] + src[p + 3];
        out[Y * W + X] = s >> 4;
      }
    }
    return out;
  }

  function pyramid(rgba, w, h) {
    var l1 = luma(rgba, w, h);
    var l4 = down4(l1, w, h);
    var l16 = down4(l4, w >> 2, h >> 2);
    return [
      { d: l16, w: w >> 4, h: h >> 4 },
      { d: l4, w: w >> 2, h: h >> 2 },
      { d: l1, w: w, h: h }
    ];
  }

  // Diferença absoluta média entre a[y][x] e b[y+dy][x+dx].
  function sad(a, b, W, H, dx, dy, step, m) {
    var s = 0, c = 0;
    for (var y = m; y < H - m; y += step) {
      var p = y * W + m, q = (y + dy) * W + m + dx;
      for (var x = m; x < W - m; x += step, p += step, q += step) {
        var d = a[p] - b[q];
        s += d < 0 ? -d : d;
        c++;
      }
    }
    return c ? s / c : Infinity;
  }

  function search(A, B, cx, cy, r, step) {
    var W = A.w, H = A.h;
    var m = Math.max(Math.abs(cx), Math.abs(cy)) + r + 1;
    var best = { dx: cx, dy: cy, cost: Infinity };
    if (m * 2 + step >= Math.min(W, H)) return best;
    for (var dy = cy - r; dy <= cy + r; dy++) {
      for (var dx = cx - r; dx <= cx + r; dx++) {
        var c = sad(A.d, B.d, W, H, dx, dy, step, m);
        // empate favorece o deslocamento menor
        if (c < best.cost - 1e-9) best = { dx: dx, dy: dy, cost: c };
      }
    }
    return best;
  }

  // Deslocamento (dx,dy) tal que frame[y+dy][x+dx] ≈ ref[y][x].
  function align(ref, cur) {
    var a = search(ref[0], cur[0], 0, 0, 6, 1);          // ±96 px
    var b = search(ref[1], cur[1], a.dx * 4, a.dy * 4, 3, 2);
    var c = search(ref[2], cur[2], b.dx * 4, b.dy * 4, 2, 4);
    if (!isFinite(c.cost)) return { dx: 0, dy: 0, cost: sad(ref[2].d, cur[2].d, ref[2].w, ref[2].h, 0, 0, 4, 1) };
    return c;
  }

  function Merger(w, h) {
    this.w = w; this.h = h; this.n = w * h;
    this.sum = new Uint16Array(this.n * 3);
    this.ws = new Uint8Array(this.n);
    this.ref = null; this.pyr = null;
    this.frames = 0; this.used = 0; this.noise = Infinity;
  }

  Merger.prototype.setRef = function (rgba) {
    var n = this.n, sum = this.sum;
    this.ref = rgba;
    this.pyr = pyramid(rgba, this.w, this.h);
    for (var i = 0, j = 0, s = 0; i < n; i++, j += 4, s += 3) {
      sum[s] = rgba[j] << 2; sum[s + 1] = rgba[j + 1] << 2; sum[s + 2] = rgba[j + 2] << 2;
    }
    this.ws.fill(4);
    this.frames = 1; this.used = 1;
  };

  Merger.prototype.add = function (rgba) {
    if (this.frames >= MAX_FRAMES) return { skipped: true };
    this.frames++;
    var w = this.w, h = this.h;
    var al = align(this.pyr, pyramid(rgba, w, h));
    if (al.cost < this.noise) this.noise = al.cost;
    // quadro muito diferente da referência (cena mudou / borrou): descarta
    if (al.cost > this.noise * 4 + 6) return { skipped: true, dx: al.dx, dy: al.dy, cost: al.cost };

    // d = soma das diferenças absolutas RGB contra a referência.
    // Até t: peso cheio. De t a 2t: rampa. Acima: movimento → fica só a referência.
    var t = Math.max(12, Math.min(72, Math.round(this.noise * 8)));
    var lut = new Uint8Array(766);
    for (var d0 = 0; d0 < 766; d0++) lut[d0] = d0 <= t ? 4 : d0 >= 2 * t ? 0 : ((2 * t - d0) * 4 / t) | 0;

    var dx = al.dx, dy = al.dy, ref = this.ref, sum = this.sum, ws = this.ws;
    var x0 = Math.max(0, -dx), x1 = Math.min(w, w - dx);
    var y0 = Math.max(0, -dy), y1 = Math.min(h, h - dy);
    for (var y = y0; y < y1; y++) {
      var i = y * w + x0, k = i * 4, s = i * 3, j = ((y + dy) * w + x0 + dx) * 4;
      for (var x = x0; x < x1; x++, i++, k += 4, s += 3, j += 4) {
        var r = rgba[j], g = rgba[j + 1], b = rgba[j + 2];
        var e = r - ref[k], d = e < 0 ? -e : e;
        e = g - ref[k + 1]; d += e < 0 ? -e : e;
        e = b - ref[k + 2]; d += e < 0 ? -e : e;
        var wt = lut[d];
        if (wt) { sum[s] += wt * r; sum[s + 1] += wt * g; sum[s + 2] += wt * b; ws[i] += wt; }
      }
    }
    this.used++;
    return { dx: dx, dy: dy, cost: al.cost };
  };

  // Média móvel separável com normalização nas bordas.
  function box(src, W, H, r) {
    var tmp = new Float32Array(W * H), out = new Float32Array(W * H), x, y, s, c, p;
    for (y = 0; y < H; y++) {
      p = y * W; s = 0; c = 0;
      for (x = 0; x <= r && x < W; x++) { s += src[p + x]; c++; }
      for (x = 0; x < W; x++) {
        tmp[p + x] = s / c;
        if (x + r + 1 < W) { s += src[p + x + r + 1]; c++; }
        if (x - r >= 0) { s -= src[p + x - r]; c--; }
      }
    }
    for (x = 0; x < W; x++) {
      s = 0; c = 0;
      for (y = 0; y <= r && y < H; y++) { s += tmp[y * W + x]; c++; }
      for (y = 0; y < H; y++) {
        out[y * W + x] = s / c;
        if (y + r + 1 < H) { s += tmp[(y + r + 1) * W + x]; c++; }
        if (y - r >= 0) { s -= tmp[(y - r) * W + x]; c--; }
      }
    }
    return out;
  }

  function smooth(a, b, x) {
    var t = (x - a) / (b - a);
    t = t < 0 ? 0 : t > 1 ? 1 : t;
    return t * t * (3 - 2 * t);
  }

  /*
   * opts.night: mais levantamento de sombras, menos nitidez.
   * Retorna RGBA (reaproveita o buffer da referência).
   */
  Merger.prototype.finish = function (opts) {
    opts = opts || {};
    var night = !!opts.night;
    var w = this.w, h = this.h, n = this.n, sum = this.sum, ws = this.ws;
    var out = this.ref; this.ref = null; this.pyr = null;
    var i, s, x, y;

    // Luma fundida, ponto fixo ×16.
    var Y = new Uint16Array(n);
    for (i = 0, s = 0; i < n; i++, s += 3) {
      Y[i] = (sum[s] * 77 + sum[s + 1] * 150 + sum[s + 2] * 29) / (ws[i] * 16);
    }

    // Camada-base em 1/8 com filtro guiado; coeficientes A,B sobem em bilinear
    // e são aplicados na luma cheia → base que respeita bordas, sem halo grosso.
    var S = 8, W8 = Math.max(1, (w / S) | 0), H8 = Math.max(1, (h / S) | 0), m = W8 * H8;
    var I = new Float32Array(m), II = new Float32Array(m);
    for (y = 0; y < H8; y++) {
      for (x = 0; x < W8; x++) {
        var acc = 0, p = y * S * w + x * S;
        for (var yy = 0; yy < S; yy += 2, p += 2 * w) acc += Y[p] + Y[p + 2] + Y[p + 4] + Y[p + 6];
        var v = acc / (16 * 16 * 255);
        I[y * W8 + x] = v; II[y * W8 + x] = v * v;
      }
    }
    var rad = Math.max(3, Math.round(Math.min(W8, H8) / 20));
    var mI = box(I, W8, H8, rad), mII = box(II, W8, H8, rad);
    var a = new Float32Array(m), b = new Float32Array(m), eps = 0.006;
    for (i = 0; i < m; i++) {
      var va = mII[i] - mI[i] * mI[i]; if (va < 0) va = 0;
      a[i] = va / (va + eps);
      b[i] = mI[i] - a[i] * mI[i];
    }
    var A = box(a, W8, H8, rad), B = box(b, W8, H8, rad);

    // Exposição adaptativa: gama escolhido pela "chave" (média log) da cena.
    var ls = 0;
    for (i = 0; i < m; i++) ls += Math.log(A[i] * I[i] + B[i] + 0.01);
    var key = Math.exp(ls / m);
    var g = Math.log(night ? 0.42 : 0.38) / Math.log(Math.min(0.95, key));
    g = night ? Math.max(0.45, Math.min(0.85, g)) : Math.max(0.6, Math.min(0.92, g));
    var maxGain = night ? 5 : 3.5, hl = 0.10;
    var gainLUT = new Float32Array(1024);
    for (i = 0; i < 1024; i++) {
      var bx = Math.max(0.02, i / 1023), c = Math.pow(bx, g);
      c -= hl * smooth(0.55, 1, bx) * c;
      gainLUT[i] = Math.min(maxGain, c / bx);
    }

    // Curva em S final (entrada ×4 → 10 bits de precisão).
    var curve = new Uint8ClampedArray(1024), kS = 0.35;
    for (i = 0; i < 1024; i++) {
      var t = i / 1023;
      curve[i] = Math.round((t + kS * t * (1 - t) * (2 * t - 1)) * 255);
    }

    // Pesos da interpolação horizontal de A/B.
    var xi = new Int32Array(w), xf = new Float32Array(w);
    for (x = 0; x < w; x++) {
      var fx = (x + 0.5) / S - 0.5;
      if (fx < 0) fx = 0; if (fx > W8 - 1) fx = W8 - 1;
      var ix = Math.min(W8 - 2, fx | 0); if (ix < 0) ix = 0;
      xi[x] = ix; xf[x] = W8 > 1 ? fx - ix : 0;
    }
    var rowA = new Float32Array(W8 + 1), rowB = new Float32Array(W8 + 1);

    var amt = night ? 0.6 : 1.1, core = night ? 28 : 19 /* ×16 */, lim = 20 * 16;
    var vib = 0.22, warmR = 1.015, warmB = 0.985;

    for (y = 0; y < h; y++) {
      var fy = (y + 0.5) / S - 0.5;
      if (fy < 0) fy = 0; if (fy > H8 - 1) fy = H8 - 1;
      var iy = Math.min(H8 - 2, fy | 0); if (iy < 0) iy = 0;
      var wy = H8 > 1 ? fy - iy : 0, r0 = iy * W8, r1 = (H8 > 1 ? iy + 1 : iy) * W8;
      for (x = 0; x < W8; x++) {
        rowA[x] = A[r0 + x] * (1 - wy) + A[r1 + x] * wy;
        rowB[x] = B[r0 + x] * (1 - wy) + B[r1 + x] * wy;
      }
      rowA[W8] = rowA[W8 - 1]; rowB[W8] = rowB[W8 - 1];
      var inner = y > 0 && y < h - 1;
      i = y * w; s = i * 3; var o = i * 4;
      for (x = 0; x < w; x++, i++, s += 3, o += 4) {
        var yc = Y[i], q = xi[x], f = xf[x];
        var base = (rowA[q] + (rowA[q + 1] - rowA[q]) * f) * (yc / 4080) + rowB[q] + (rowB[q + 1] - rowB[q]) * f;
        var gi = (base * 1023) | 0; if (gi < 0) gi = 0; else if (gi > 1023) gi = 1023;
        var gain = gainLUT[gi];

        // nitidez: detalhe = luma − desfoque 3×3, com coring para não realçar ruído
        var dd = 0;
        if (inner && x > 0 && x < w - 1) {
          dd = yc - (4 * yc + 2 * (Y[i - 1] + Y[i + 1] + Y[i - w] + Y[i + w]) +
            Y[i - w - 1] + Y[i - w + 1] + Y[i + w - 1] + Y[i + w + 1]) / 16;
          dd = dd > core ? dd - core : dd < -core ? dd + core : 0;
          if (dd > lim) dd = lim; else if (dd < -lim) dd = -lim;
          dd = dd * amt / 16;
        }

        var inv = 1 / ws[i];
        var r = (sum[s] * inv + dd) * gain * warmR;
        var gg = (sum[s + 1] * inv + dd) * gain;
        var bb = (sum[s + 2] * inv + dd) * gain * warmB;

        // vibrance: satura mais o que está pouco saturado
        var mx = r > gg ? (r > bb ? r : bb) : (gg > bb ? gg : bb);
        var mn = r < gg ? (r < bb ? r : bb) : (gg < bb ? gg : bb);
        var vf = 1 + vib * (1 - (mx - mn) / (mx + 1));
        var yl = r * 0.299 + gg * 0.587 + bb * 0.114;
        r = yl + (r - yl) * vf; gg = yl + (gg - yl) * vf; bb = yl + (bb - yl) * vf;

        var c1 = (r * 4 + 0.5) | 0, c2 = (gg * 4 + 0.5) | 0, c3 = (bb * 4 + 0.5) | 0;
        out[o] = curve[c1 < 0 ? 0 : c1 > 1023 ? 1023 : c1];
        out[o + 1] = curve[c2 < 0 ? 0 : c2 > 1023 ? 1023 : c2];
        out[o + 2] = curve[c3 < 0 ? 0 : c3 > 1023 ? 1023 : c3];
        out[o + 3] = 255;
      }
    }
    this.sum = null; this.ws = null;
    return { rgba: out, stats: { frames: this.frames, used: this.used, gamma: g, key: key, noise: this.noise } };
  };

  var api = { Merger: Merger, align: align, pyramid: pyramid, MAX_FRAMES: MAX_FRAMES };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.BitPipeline = api;
})(typeof self !== 'undefined' ? self : this);
