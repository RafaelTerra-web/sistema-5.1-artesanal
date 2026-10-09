(() => {
  'use strict';
  if (location.origin !== 'https://www.youtube.com' || window.top !== window) return;
  // Extension assets only; no page secrets, local-server token or remote fetch.
  const announce = () => window.postMessage({type: 'sistema51-source-bootstrap', version: 1,
    workletUrl: chrome.runtime.getURL('source-worklet.js')}, location.origin);
  announce();
  for (const delay of [500, 1500, 3000]) setTimeout(announce, delay);
})();
