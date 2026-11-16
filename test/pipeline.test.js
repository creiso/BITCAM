/* node test/pipeline.test.js — cena sintética com ruído, tremor e objeto em movimento. */
'use strict';
const assert = require('assert');
const { Merger, align, pyramid } = require('../pipeline.js');

const W = 640, H = 480, PAD = 40;
let seed = 12345;
const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296;
const gauss = () => { let s = 0; for (let i = 0; i < 6; i++) s += rnd(); return (s - 3) * Math.SQRT2; };

// Cena "limpa" maior que o quadro, para poder deslocar.
const SW = W + PAD * 2, SH = H + PAD * 2;
const scene = new Float32Array(SW * SH * 3);
const BW = (SW >> 5) + 1, blocks = new Float32Array(BW * ((SH >> 5) + 1)).map(() => rnd() * 70);
for (let y = 0; y < SH; y++) for (let x = 0; x < SW; x++) {
  const i = (y * SW + x) * 3;
  const blk = blocks[(y >> 5) * BW + (x >> 5)];
  const tex = 20 * Math.sin(x * 0.21) * Math.cos(y * 0.17);
  scene[i] = 60 + blk + tex + x * 0.08;
  scene[i + 1] = 70 + blk + tex + y * 0.1;
  scene[i + 2] = 50 + blk + tex;
}

function shot(dx, dy, sigma, ball) {
  const out = new Uint8ClampedArray(W * H * 4);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const s = ((y + PAD + dy) * SW + x + PAD + dx) * 3, o = (y * W + x) * 4;
    let r = scene[s], g = scene[s + 1], b = scene[s + 2];
    if (ball && (x - ball.x) ** 2 + (y - ball.y) ** 2 < 30 * 30) { r = 230; g = 40; b = 40; }
    out[o] = r + gauss() * sigma; out[o + 1] = g + gauss() * sigma; out[o + 2] = b + gauss() * sigma; out[o + 3] = 255;
  }
  return out;
}

// 1. Alinhamento recupera o deslocamento exato.
{
  const ref = shot(0, 0, 6), cur = shot(13, -7, 6);
  const al = align(pyramid(ref, W, H), pyramid(cur, W, H));
  assert.deepStrictEqual([al.dx, al.dy], [-13, 7], 'deslocamento errado: ' + JSON.stringify(al));
  console.log('ok  alinhamento', al.dx, al.dy);
}

// 2. Fusão reduz ruído e não deixa fantasma do objeto em movimento.
{
  const sigma = 10, N = 8;
  const shifts = [[0, 0], [3, -2], [-5, 4], [8, 1], [-2, -6], [1, 7], [-9, -3], [4, 4]];
  const m = new Merger(W, H);
  const refFrame = shot(0, 0, sigma, { x: 200, y: 200 });
  const refCopy = refFrame.slice();
  m.setRef(refFrame);
  for (let k = 1; k < N; k++) {
    const info = m.add(shot(shifts[k][0], shifts[k][1], sigma, { x: 200 + k * 25, y: 200 }));
    assert.ok(!info.skipped, 'quadro descartado');
    assert.deepStrictEqual([info.dx, info.dy], [-shifts[k][0], -shifts[k][1]]);
  }
  // erro do merge antes do acabamento
  const rms = (get) => {
    let e = 0, c = 0;
    for (let y = 60; y < H - 60; y++) for (let x = 320; x < W - 60; x++) { // região estática
      const s = ((y + PAD) * SW + x + PAD) * 3;
      for (let ch = 0; ch < 3; ch++) { const d = get(y * W + x, ch) - scene[s + ch]; e += d * d; c++; }
    }
    return Math.sqrt(e / c);
  };
  const single = rms((i, ch) => refCopy[i * 4 + ch]);
  const merged = rms((i, ch) => m.sum[i * 3 + ch] / m.ws[i]);
  console.log('ok  ruído RMS: 1 quadro', single.toFixed(2), '→ fundido', merged.toFixed(2));
  assert.ok(merged < single * 0.55, 'redução de ruído insuficiente');

  // onde a bola estava na referência, os outros quadros (fundo) devem ser rejeitados
  const bi = 200 * W + 200;
  assert.ok(m.sum[bi * 3] / m.ws[bi] > 180, 'fantasma: bola da referência foi diluída');
  // onde a bola passou depois, deve permanecer o fundo
  const gi = 200 * W + 300, gs = ((200 + PAD) * SW + 300 + PAD) * 3;
  assert.ok(Math.abs(m.sum[gi * 3] / m.ws[gi] - scene[gs]) < 25, 'fantasma: rastro da bola');
  console.log('ok  sem fantasmas');

  const res = m.finish({});
  assert.strictEqual(res.rgba.length, W * H * 4);
  let mean = 0; for (let i = 0; i < W * H; i++) mean += res.rgba[i * 4 + 1];
  mean /= W * H;
  assert.ok(mean > 60 && mean < 200, 'exposição final fora do esperado: ' + mean);
  console.log('ok  acabamento, média G', mean.toFixed(1), 'gama', res.stats.gamma.toFixed(2));
}

// 3. Cena escura no modo noite fica mais clara que no modo foto.
{
  const dark = (night) => {
    const m = new Merger(W, H);
    const f = shot(0, 0, 3); for (let i = 0; i < f.length; i += 4) { f[i] >>= 2; f[i + 1] >>= 2; f[i + 2] >>= 2; }
    m.setRef(f);
    const out = m.finish({ night }).rgba;
    let s = 0; for (let i = 0; i < W * H; i++) s += out[i * 4 + 1];
    return s / (W * H);
  };
  const a = dark(false), b = dark(true);
  console.log('ok  cena escura: foto', a.toFixed(1), 'noite', b.toFixed(1));
  assert.ok(b > a + 5);
}
console.log('tudo certo');
