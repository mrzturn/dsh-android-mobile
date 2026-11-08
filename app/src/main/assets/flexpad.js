/* DSH Mobile FlexPad bridge — injected into the official Web GUI.
 * small QoL patches (viewport, keyboard, hover suppression, phone composer
 * one-line controls).
 * The native side calls window.DshMobileFlexpad.* via evaluateJavascript;
 * results flow back through the injected DshMobileAndroid JavascriptInterface.
 */
(function () {
  'use strict';
  if (window.DshMobileFlexpad) return;

  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  function pointerEvent(type, x, y, opts) {
    opts = opts || {};
    var el = document.elementFromPoint(x, y);
    if (!el) return 'no-target';
    var ev = new PointerEvent(type, {
      bubbles: true, cancelable: true, composed: true,
      pointerId: opts.pointerId || 9001, pointerType: 'mouse', isPrimary: true,
      clientX: x, clientY: y, screenX: x, screenY: y,
      button: opts.button != null ? opts.button : 0,
      buttons: opts.buttons != null ? opts.buttons : (type === 'pointerup' || type === 'pointercancel' ? 0 : 1),
      detail: opts.detail || 0,
      ctrlKey: !!opts.ctrlKey, shiftKey: !!opts.shiftKey, altKey: !!opts.altKey, metaKey: !!opts.metaKey,
    });
    el.dispatchEvent(ev);
    return 'ok';
  }

  // Fire a full click sequence (move-down-up) at a point.
  function clickAt(x, y, opts) {
    pointerEvent('pointermove', x, y, opts);
    pointerEvent('pointerdown', x, y, Object.assign({ detail: 1 }, opts));
    pointerEvent('pointerup', x, y, Object.assign({ buttons: 0 }, opts));
    var el = document.elementFromPoint(x, y);
    if (el) {
      var click = new MouseEvent('click', { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, detail: 1, button: 0 });
      el.dispatchEvent(click);
    }
    return 'ok';
  }

  // Drag: press, move in steps, release (used by touchpad drag mode).
  function drag(x1, y1, x2, y2, steps) {
    steps = Math.max(2, steps | 0 || 12);
    pointerEvent('pointermove', x1, y1, {});
    pointerEvent('pointerdown', x1, y1, { buttons: 1 });
    for (var i = 1; i <= steps; i++) {
      var x = x1 + (x2 - x1) * i / steps;
      var y = y1 + (y2 - y1) * i / steps;
      pointerEvent('pointermove', x, y, { buttons: 1 });
    }
    pointerEvent('pointerup', x2, y2, { buttons: 0 });
    return 'ok';
  }

  // Two-finger scroll gesture emulation.
  function scrollBy(x, y, dx, dy) {
    var el = document.elementFromPoint(x, y);
    if (!el) return 'no-target';
    for (var i = 0; i < 6; i++) {
      var ev = new WheelEvent('wheel', {
        bubbles: true, cancelable: true, composed: true,
        clientX: x, clientY: y, deltaX: dx / 6, deltaY: dy / 6, deltaMode: 0,
      });
      el.dispatchEvent(ev);
    }
    return 'ok';
  }

  // Dispatch a real-ish KeyboardEvent on the active element.
  function sendKey(key, keyCode, ctrl, shift, alt) {
    var target = document.activeElement || document.body;
    var init = {
      bubbles: true, cancelable: true, composed: true,
      key: key, code: CODE_MAP[key] || key, keyCode: keyCode, which: keyCode,
      ctrlKey: !!ctrl, shiftKey: !!shift, altKey: !!alt, metaKey: false,
      location: 0, repeat: false,
    };
    // keyCode/which are read-only on init dicts; set via prototype hack for legacy listeners.
    var down = new KeyboardEvent('keydown', init);
    try { Object.defineProperty(down, 'keyCode', { get: function () { return keyCode; } });
          Object.defineProperty(down, 'which', { get: function () { return keyCode; } }); } catch (e) {}
    target.dispatchEvent(down);
    var up = new KeyboardEvent('keyup', init);
    try { Object.defineProperty(up, 'keyCode', { get: function () { return keyCode; } });
          Object.defineProperty(up, 'which', { get: function () { return keyCode; } }); } catch (e) {}
    target.dispatchEvent(up);
    return 'ok';
  }
  var CODE_MAP = {
    Enter: 'Enter', Escape: 'Escape', Tab: 'Tab', ArrowUp: 'ArrowUp', ArrowDown: 'ArrowDown',
    ArrowLeft: 'ArrowLeft', ArrowRight: 'ArrowRight', Backspace: 'Backspace', ' ': 'Space',
  };

  // ---- Phone composer: one-line controls ---------------------------------
  // Portrait adaptation layers (the remote-UI plugin's mobile-adapt.css, and
  // narrow official layouts) wrap the composer control row: the trailing
  // group (model picker, voice, send) is forced onto its own full-width row
  // (flex-basis:100%), which stacks the permission picker and the model
  // picker vertically on phones. Keep them on one line instead: the
  // permission/plan seats stay left, the model + voice controls hug the
  // pinned send button, and the model picker stays icon-only through the
  // official compact variables. Scoped to coarse pointers <=1100px (the
  // plugin's own portrait threshold), so desktops are untouched.
  var MOBILE_CSS_ID = "dshMobileFlexpad/mobile-composer.css";
  var MOBILE_CSS =
    "@media (pointer:coarse) and (max-width:1100px){" +
      "body [data-composer-seat] [class$=\"_row\"]," +
      "body [class$=\"_composerSeat\"] [class$=\"_row\"]" +
      "{--dsh-composer-model-text-display:none;--dsh-composer-model-icon-display:block}" +
      "body [data-composer-seat] [class$=\"_trailing\"]," +
      "body [data-composer-seat] [class$=\"_trailingActive\"]," +
      "body [class$=\"_composerSeat\"] [class$=\"_trailing\"]," +
      "body [class$=\"_composerSeat\"] [class$=\"_trailingActive\"]" +
      "{flex:1 1 auto;justify-content:flex-end}" +
    "}";
  function ensureMobileComposerCss() {
    if (typeof document === "undefined") return;
    if (document.querySelector("style[data-plugin-css=\"" + MOBILE_CSS_ID + "\"]")) return;
    var parent = document.head || document.documentElement;
    if (!parent) return;
    var tag = document.createElement("style");
    tag.setAttribute("data-plugin-css", MOBILE_CSS_ID);
    tag.textContent = MOBILE_CSS;
    parent.appendChild(tag);
  }
  ensureMobileComposerCss();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", ensureMobileComposerCss, { once: true });
  }

  window.DshMobileFlexpad = {
    version: 2,
    clickAt: clickAt,
    drag: drag,
    scrollBy: scrollBy,
    move: function (x, y) { return pointerEvent('pointermove', x, y, {}); },
    down: function (x, y) { return pointerEvent('pointerdown', x, y, { buttons: 1 }); },
    up: function (x, y) { return pointerEvent('pointerup', x, y, { buttons: 0 }); },
    /** Right-click (long-press equivalent on the touchpad). */
    rightClick: function (x, y) {
      pointerEvent('pointermove', x, y, {});
      pointerEvent('pointerdown', x, y, { button: 2, buttons: 2 });
      pointerEvent('pointerup', x, y, { button: 2, buttons: 0 });
      var el = document.elementFromPoint(x, y);
      if (el) {
        el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y }));
      }
      return 'ok';
    },
    /** Composer focus helper: focus the last contenteditable/input in the composer area. */
    sendKey: sendKey,
    focusComposer: function () {
      var nodes = document.querySelectorAll('[class*="composerSeat"] textarea, [class*="composerSeat"] [contenteditable="true"], [class*="composer"] textarea, [contenteditable="true"]');
      var el = nodes.length ? nodes[nodes.length - 1] : null;
      if (el) { el.focus(); return 'ok'; }
      return 'no-composer';
    },
    /** Page metrics for the native cursor clamp. */
    metrics: function () {
      return JSON.stringify({ w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio || 1 });
    },
  };

  // Tell the native side the bridge is ready.
  try { window.DshMobileAndroid && window.DshMobileAndroid.onBridgeReady(JSON.stringify(window.DshMobileFlexpad.metrics ? JSON.parse(window.DshMobileFlexpad.metrics()) : {})); } catch (e) {}
})();
