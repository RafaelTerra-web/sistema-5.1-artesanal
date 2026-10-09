(() => {
  'use strict';
  if (location.origin !== 'https://www.youtube.com' || window.top !== window || window.__sistema51SourceCodecCapture) return;
  const MediaSourceType = window.MediaSource, BufferType = window.SourceBuffer;
  if (!MediaSourceType || !BufferType || !window.URL?.createObjectURL) return;
  const bufferMimes = new WeakMap(), sourceInfo = new WeakMap(), objectUrls = new Map();
  let nextIdentity = 0;
  const changed = () => {
    try {document.dispatchEvent(new CustomEvent('sistema51-codec-changed'));} catch (_) {}
  };
  function observe(source) {
    let info = sourceInfo.get(source);
    if (info) return info;
    info = {identity: ++nextIdentity, revision: 0, urls: new Set()};
    sourceInfo.set(source, info);
    const update = () => {info.revision++; changed();};
    for (const list of [source.sourceBuffers, source.activeSourceBuffers]) {
      list.addEventListener('addsourcebuffer', update);
      list.addEventListener('removesourcebuffer', update);
    }
    source.addEventListener('sourceclose', () => {
      for (const url of info.urls) objectUrls.delete(url);
      info.urls.clear(); update();
    });
    return info;
  }
  function wrap(owner, name, after) {
    const descriptor = Object.getOwnPropertyDescriptor(owner, name);
    if (!descriptor || typeof descriptor.value !== 'function' || (!descriptor.configurable && !descriptor.writable)) return;
    const original = descriptor.value;
    // A concise method is non-constructible like these Web IDL operations.
    const replacement = {invoke(...args) {
      const result = Reflect.apply(original, this, args);
      // Observation must not replace a native result or throw after success.
      try {after(this, args, result);} catch (_) {}
      return result;
    }}.invoke;
    Object.defineProperty(replacement, 'name', {value: original.name, configurable: true});
    Object.defineProperty(replacement, 'length', {value: original.length, configurable: true});
    Object.defineProperty(owner, name, {...descriptor, value: replacement});
  }
  try {
    wrap(MediaSourceType.prototype, 'addSourceBuffer', (source, args, buffer) => {
      const info = observe(source);
      // Do not coerce an object argument again; its toString could have effects.
      // Such an unrecorded MIME deliberately remains unknown.
      if (typeof args[0] === 'string') bufferMimes.set(buffer, {mimes: new Set([args[0]]), source, typeChanges: 0});
      info.revision++; changed();
    });
    wrap(BufferType.prototype, 'changeType', (buffer, args) => {
      const previous = bufferMimes.get(buffer);
      if (!previous) return;
      // changeType describes future appends; earlier buffered frames may still
      // be playing. Keep the history so an AC3 -> Opus change cannot authorize
      // synthesis while the old Dolby frames remain in this buffer.
      previous.mimes.add(typeof args[0] === 'string' ? args[0] : null);
      previous.typeChanges++;
      if (previous.mimes.size > 32) previous.mimes = new Set([null]);
      observe(previous.source).revision++; changed();
    });
    wrap(MediaSourceType.prototype, 'removeSourceBuffer', (source, args) => {
      bufferMimes.delete(args[0]); observe(source).revision++; changed();
    });
    wrap(window.URL, 'createObjectURL', (_, args, result) => {
      const source = args[0];
      if (!(source instanceof MediaSourceType) || typeof result !== 'string') return;
      const info = observe(source);
      objectUrls.set(result, source); info.urls.add(result);
    });
    Object.defineProperty(window, '__sistema51SourceCodecCapture', {value: Object.freeze({version: 1, read(video) {
      if (!video || video.mediaKeys || video.srcObject) return null;
      const source = objectUrls.get(video.currentSrc || video.src);
      if (!source || source.readyState === 'closed') return null;
      const info = sourceInfo.get(source), recorded = [];
      const buffers = source.activeSourceBuffers;
      for (let index = 0; index < buffers.length; index++) {
        const entry = bufferMimes.get(buffers[index]);
        if (!entry || entry.source !== source || entry.mimes.has(null)) return {unknown: true};
        recorded.push({mimes: [...entry.mimes], typeChanges: entry.typeChanges});
      }
      if (!recorded.length) return {unknown: true};
      // No URL, media bytes, track IDs or account data leave this registry.
      return {buffers: recorded, identity: info.identity, revision: info.revision};
    }}), configurable: false, writable: false});
  } catch (_) {
    // If a browser disallows a hook, the page uses its conservative fallback.
  }
})();
