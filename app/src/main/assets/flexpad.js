/* DSH Mobile FlexPad bridge — injected into the official Web GUI.
 * Provides pointer-event synthesis for the native touchpad/quick keys and
 * small QoL patches (viewport, keyboard, hover suppression).
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

  window.DshMobileFlexpad = {
    version: 1,
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
