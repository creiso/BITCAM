/* Worker: recebe os quadros um a um, funde e devolve a foto final. */
importScripts('pipeline.js');

var merger = null;

self.onmessage = function (e) {
  var m = e.data;
  try {
    if (m.type === 'ref') {
      merger = new BitPipeline.Merger(m.w, m.h);
      merger.setRef(new Uint8ClampedArray(m.buf));
      self.postMessage({ type: 'ok' });
    } else if (m.type === 'frame') {
      var info = merger.add(new Uint8ClampedArray(m.buf));
      self.postMessage({ type: 'ok', info: info });
    } else if (m.type === 'finish') {
      var res = merger.finish(m.opts);
      merger = null;
      self.postMessage({ type: 'done', buf: res.rgba.buffer, stats: res.stats }, [res.rgba.buffer]);
    }
  } catch (err) {
    merger = null;
    self.postMessage({ type: 'error', message: String(err && err.message || err) });
  }
};
