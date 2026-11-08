/*
 * dsh-http-patch.js — injected on every page start by DshWebView.
 *
 * Works around the dsh-market relay double-compression bug at the page layer:
 * shouldInterceptRequest covers body-less requests, but POST/PUT/PATCH/DELETE
 * bodies never reach it. This patch rewrites window.fetch so every
 * body-bearing request to the session origin is executed synchronously by the
 * native tunnel proxy (window.DshMobileTunnel.http), which returns a fully
 * decoded response. Everything else (GET/HEAD, cross-origin) is left to the
 * network stack, where the interceptor already applies.
 *
 * The GUI uses fetch exclusively (verified: 11 call sites, one EventSource,
 * zero XHR), and EventSource is body-less, so this closes the gap.
 */
(function () {
  'use strict';
  if (window.__dshHttpPatch) return;
  window.__dshHttpPatch = true;

  var nativeBridge = window.DshMobileTunnel; // @JavascriptInterface object
  var originalFetch = window.fetch ? window.fetch.bind(window) : null;
  if (!nativeBridge || !originalFetch) return;

  var b64Decode = null;
  try { b64Decode = window.atob.bind(window); } catch (e) { /* noop */ }

  function base64ToBytes(b64) {
    if (!b64) return new Uint8Array(0);
    var bin = b64Decode(b64);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i) & 0xff;
    return out;
  }

  function headersToObject(input) {
    var out = {};
    if (!input) return out;
    if (typeof Headers !== 'undefined' && input instanceof Headers) {
      input.forEach(function (value, key) { out[key] = value; });
      return out;
    }
    if (Array.isArray(input)) {
      input.forEach(function (pair) { if (pair && pair.length === 2) out[pair[0]] = pair[1]; });
      return out;
    }
    for (var key in input) {
      if (Object.prototype.hasOwnProperty.call(input, key)) out[key] = input[key];
    }
    return out;
  }

  function sameOrigin(url) {
    try {
      var parsed = new URL(url, location.href);
      return parsed.origin === location.origin;
    } catch (e) { return false; }
  }

  /**
   * Synchronous native call → Response. Only reached for body-bearing
   * same-origin requests; async streaming cannot survive the sync JS bridge,
   * which is acceptable: every GUI POST is a bounded JSON exchange.
   */
  function nativeResponse(method, url, headerObject, bodyBytes) {
    var bodyB64 = '';
    if (bodyBytes && bodyBytes.length) {
      var bin = '';
      for (var i = 0; i < bodyBytes.length; i++) bin += String.fromCharCode(bodyBytes[i]);
      bodyB64 = btoa(bin);
    }
    var raw = nativeBridge.http(method, url, JSON.stringify(headerObject), bodyB64);
    var parsed = JSON.parse(raw);
    if (parsed.error) throw new TypeError('dsh tunnel proxy: ' + parsed.error);
    var bytes = base64ToBytes(parsed.bodyB64);
    var responseHeaders = new Headers();
    var responseObject = parsed.headers || {};
    for (var name in responseObject) {
      if (Object.prototype.hasOwnProperty.call(responseObject, name)) {
        try { responseHeaders.append(name, responseObject[name]); } catch (e) { /* skip */ }
      }
    }
    var status = parsed.status | 0;
    var init = { status: status, statusText: parsed.reason || '', headers: responseHeaders };
    var response;
    if (status === 204 || status === 205 || status === 304) {
      response = new Response(null, init);
    } else {
      response = new Response(new Blob([bytes]), init);
    }
    // Expose the standard helpers the GUI relies on.
    response.json = function () { return Promise.resolve(JSON.parse(new TextDecoder().decode(bytes))); };
    response.text = function () { return Promise.resolve(new TextDecoder().decode(bytes)); };
    return response;
  }

  window.fetch = function (input, init) {
    try {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var method = ((init && init.method) || (typeof input === 'object' && input && input.method) || 'GET').toUpperCase();
      var hasBody = init && ('body' in init) && init.body != null;
      if (method !== 'GET' && method !== 'HEAD' && hasBody && sameOrigin(url)) {
        var headerObject = headersToObject(init.headers);
        var bytes = null;
        if (typeof init.body === 'string') {
          bytes = new TextEncoder().encode(init.body);
        } else if (init.body instanceof Uint8Array) {
          bytes = init.body;
        } else if (typeof ArrayBuffer !== 'undefined' && init.body instanceof ArrayBuffer) {
          bytes = new Uint8Array(init.body);
        } else if (typeof Blob !== 'undefined' && init.body instanceof Blob) {
          // Blobs are async; fall back to the network stack (interceptor
          // still applies, just without this patch's guarantees).
          return originalFetch(input, init);
        } else if (typeof FormData !== 'undefined' && init.body instanceof FormData) {
          return originalFetch(input, init);
        }
        if (bytes) return Promise.resolve(nativeResponse(method, new URL(url, location.href).href, headerObject, bytes));
      }
    } catch (error) {
      return Promise.reject(error);
    }
    return originalFetch(input, init);
  };
})();
