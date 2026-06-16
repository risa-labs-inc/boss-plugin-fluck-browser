/*
 * BOSS co-browse viewer. Connects to the fluck-browser share server, performs
 * the optional E2E handshake (WebCrypto, matching BrowserSessionCrypto), renders
 * the host's focused tab with an rrweb Replayer (live mode), shows all remote
 * tabs in a bar, and — when control is granted — forwards semantic input back.
 */
(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };
  var enc = function (s) { return new TextEncoder().encode(s); };

  // --- params ---
  var params = new URLSearchParams(location.search);
  var token = params.get("t") || "";
  var hashMatch = location.hash.match(/[#&]k=([^&]+)/);
  var secretB64 = hashMatch ? hashMatch[1] : "";

  // --- persistent client identity + grant key ---
  function loadOrMakeClientId() {
    var k = "boss-cobrowse-clientId";
    var v = localStorage.getItem(k);
    if (!v) { v = (crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2)); localStorage.setItem(k, v); }
    return v;
  }
  var clientId = loadOrMakeClientId();
  var keyStoreId = "boss-cobrowse-key-" + token;
  function loadKey() { return localStorage.getItem(keyStoreId); }
  function saveKey(k) { try { localStorage.setItem(keyStoreId, k); } catch (e) {} }
  function clearKey() { try { localStorage.removeItem(keyStoreId); } catch (e) {} }

  // --- base64url ---
  function b64urlToBytes(s) {
    s = s.replace(/-/g, "+").replace(/_/g, "/");
    while (s.length % 4) s += "=";
    var bin = atob(s), a = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    return a;
  }
  function bytesToB64url(a) {
    var s = "";
    for (var i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);
    return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }
  function concat(a, b) { var o = new Uint8Array(a.length + b.length); o.set(a, 0); o.set(b, a.length); return o; }
  function bytesEqual(a, b) { if (a.length !== b.length) return false; var d = 0; for (var i = 0; i < a.length; i++) d |= a[i] ^ b[i]; return d === 0; }

  // --- crypto (mirrors BrowserSessionCrypto) ---
  var subtle = (window.crypto && window.crypto.subtle) ? window.crypto.subtle : null;
  var useE2E = !!(secretB64 && subtle);
  var encKey = null, decKey = null; // AES-GCM keys (C2S encrypt, S2C decrypt)
  var DIR_C2S = new Uint8Array([0]);
  var DIR_S2C = new Uint8Array([1]);

  async function deriveAll(secret, saltC, saltS) {
    var salt = concat(saltC, saltS);
    var ikm = await subtle.importKey("raw", secret, "HKDF", false, ["deriveBits"]);
    async function d(label) {
      var bits = await subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: salt, info: enc(label) }, ikm, 256);
      return new Uint8Array(bits);
    }
    return { kC2s: await d("bossbrowser-c2s-v1"), kS2c: await d("bossbrowser-s2c-v1"), confirm: await d("bossbrowser-kc-v1") };
  }
  async function importGcm(raw, usage) { return subtle.importKey("raw", raw, { name: "AES-GCM" }, false, [usage]); }
  async function encryptFrame(text) {
    var iv = crypto.getRandomValues(new Uint8Array(12));
    var ct = await subtle.encrypt({ name: "AES-GCM", iv: iv, additionalData: DIR_C2S, tagLength: 128 }, encKey, enc(text));
    return concat(iv, new Uint8Array(ct)).buffer;
  }
  async function decryptFrame(buf) {
    var a = new Uint8Array(buf), iv = a.slice(0, 12), body = a.slice(12);
    var pt = await subtle.decrypt({ name: "AES-GCM", iv: iv, additionalData: DIR_S2C, tagLength: 128 }, decKey, body);
    return new TextDecoder().decode(pt);
  }

  // --- UI ---
  var statusEl = $("status"), statusText = $("statustext");
  function showStatus(t, spin) {
    statusText.textContent = t;
    statusEl.classList.remove("hidden");
    statusEl.querySelector(".spinner").style.display = spin === false ? "none" : "";
  }
  function hideStatus() { statusEl.classList.add("hidden"); }

  // --- state ---
  var ws = null;
  var phase = "connecting"; // connecting -> (kex) -> stream
  var saltC = null;
  var controlGranted = false;
  var terminal = false; // a terminal status (denied) was shown; don't clobber with 'Disconnected'
  var tabs = [], activeTabId = null;
  var recvChain = Promise.resolve();

  // --- WebRTC (optional low-latency transport; falls back to WS) ---
  var pc = null, inputDc = null, domDc = null, rtcInput = false, rtcVideoEl = null;
  var _domChunks = {}; // reassembly buffer for chunked DOM frames over the data channel

  // --- rrweb replayer (live) ---
  var replayer = null, building = false, evBuffer = [];
  var stage = $("stage");
  var screenEl = $("screen"); // the centered, scaled "device" frame the mirror lives in
  function destroyReplayer() {
    if (replayer) { try { replayer.pause(); } catch (e) {} }
    // remove any prior rrweb iframe/wrapper
    Array.prototype.slice.call(screenEl.querySelectorAll(".replayer-wrapper, iframe")).forEach(function (n) { n.remove(); });
    replayer = null; building = false; evBuffer = [];
  }
  function resetReplayer() { destroyReplayer(); building = true; }
  function feedEvent(ev) {
    if (building) {
      evBuffer.push(ev);
      if (ev.type === 2) { // FullSnapshot present -> safe to construct
        try {
          replayer = new rrweb.Replayer(evBuffer, { liveMode: true, root: screenEl, mouseTail: false, UNSAFE_replayCanvas: false });
          replayer.startLive(evBuffer[0].timestamp);
          building = false; evBuffer = [];
          // rrweb reopens the iframe document on every full snapshot, which drops any
          // listeners bound to it — so (re)attach control listeners on each rebuild.
          replayer.on("fullsnapshot-rebuilded", attachControlListeners);
          attachControlListeners();
          hideStatus();
        } catch (e) { console.warn("replayer build failed", e); }
      }
    } else if (replayer) {
      try { replayer.addEvent(ev); } catch (e) {}
    }
  }

  // --- control capture (OVERLAY in the viewer's own document) ---
  // We do NOT listen inside the rrweb replay iframe: that document is sandboxed
  // and rrweb reopens it on every full snapshot, detaching listeners. Instead a
  // transparent overlay div sits on top of the mirror in OUR document and
  // captures all input. Coordinates are mapped from overlay space into the host
  // viewport (the iframe's intrinsic size), so the host receives correct
  // clientX/Y regardless of any CSS scale applied to fit the stage.
  var captureOverlay = null;
  var moveThrottle = 0, buttonsDown = 0;
  var SCROLL_SCALE = 1.0; // wheel sensitivity (1.0 ≈ native pixel-for-pixel)

  function ensureOverlay() {
    if (captureOverlay) return captureOverlay;
    var ov = document.createElement("div");
    ov.id = "captureOverlay";
    ov.tabIndex = 0;
    ov.style.cssText = "position:absolute;inset:0;z-index:50;outline:none;background:transparent;cursor:default;";
    ov.style.display = "none";
    screenEl.appendChild(ov);
    captureOverlay = ov;
    wireOverlay(ov);
    return ov;
  }

  // Map an overlay pointer event to host-viewport CSS px using the iframe rect.
  function toHost(e) {
    var ifr = replayer && replayer.iframe;
    if (!ifr) return null;
    var r = ifr.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return null;
    var scaleX = r.width / (ifr.offsetWidth || r.width);
    var scaleY = r.height / (ifr.offsetHeight || r.height);
    var x = (e.clientX - r.left) / (scaleX || 1);
    var y = (e.clientY - r.top) / (scaleY || 1);
    // Ignore clicks on letterbox area outside the mirrored viewport.
    if (x < 0 || y < 0 || x > (ifr.offsetWidth || r.width) || y > (ifr.offsetHeight || r.height)) return null;
    return { x: Math.round(x), y: Math.round(y) };
  }

  function wireOverlay(ov) {
    function mark(t) { _dbg.evt = t + (controlGranted ? "" : " (no-ctrl)"); hud(); }
    ov.addEventListener("mousedown", function (e) {
      mark("mousedown"); e.preventDefault();
      if (!controlGranted) { promptControl(); return; }
      ov.focus(); buttonsDown++;
      var p = toHost(e); if (!p) return;
      send({ t: "ptr", tabId: activeTabId, kind: "down", x: p.x, y: p.y, button: e.button || 0, clicks: e.detail || 1 });
    });
    ov.addEventListener("mouseup", function (e) {
      e.preventDefault();
      if (!controlGranted) return;
      buttonsDown = Math.max(0, buttonsDown - 1);
      var p = toHost(e); if (!p) return;
      send({ t: "ptr", tabId: activeTabId, kind: "up", x: p.x, y: p.y, button: e.button || 0, clicks: e.detail || 1 });
    });
    ov.addEventListener("contextmenu", function (e) { e.preventDefault(); });
    ov.addEventListener("mousemove", function (e) {
      if (!controlGranted) return;
      var now = Date.now();
      if (now - moveThrottle < 16) return; // ~60 Hz for snappier hover feedback
      moveThrottle = now;
      var p = toHost(e); if (!p) return;
      send({ t: "ptr", tabId: activeTabId, kind: buttonsDown > 0 ? "drag" : "move", x: p.x, y: p.y });
    });
    ov.addEventListener("wheel", function (e) {
      e.preventDefault();
      if (!controlGranted) return;
      var p = toHost(e); if (!p) return;
      // Normalize the wheel delta to pixels across input modes, then forward at
      // (near) 1:1 — JxBrowser's UNIT_SCROLL delta is pixel-magnitude, so the old
      // /40 made a full swipe scroll only a couple px (very slow). SCROLL_SCALE
      // tunes overall sensitivity.
      var ux = e.deltaX, uy = e.deltaY;
      if (e.deltaMode === 1) { ux *= 16; uy *= 16; }            // lines -> px
      else if (e.deltaMode === 2) {                              // pages -> px
        var ifr = replayer && replayer.iframe;
        ux *= (ifr ? ifr.offsetWidth : 800);
        uy *= (ifr ? ifr.offsetHeight : 600);
      }
      send({ t: "whl", tabId: activeTabId, x: p.x, y: p.y, dx: -ux * SCROLL_SCALE, dy: -uy * SCROLL_SCALE });
    }, { passive: false });
    function fwdKey(kind) {
      return function (e) {
        if (kind === "keydown") mark("keydown");
        if (!controlGranted) return;
        e.preventDefault();
        send({
          t: "keyn", tabId: activeTabId, kind: kind,
          key: e.key || "", code: e.code || "",
          ch: (e.key && e.key.length === 1) ? e.key : "",
          shift: !!e.shiftKey, ctrl: !!e.ctrlKey, alt: !!e.altKey, meta: !!e.metaKey
        });
      };
    }
    // Keys fire only while the overlay holds focus (set on mousedown), so typing
    // in the viewer's own address bar is never forwarded.
    ov.addEventListener("keydown", fwdKey("keydown"));
    ov.addEventListener("keyup", fwdKey("keyup"));
  }

  // Called after each replayer (re)build: show + position the overlay over the mirror.
  function attachControlListeners() {
    if (!replayer || !replayer.iframe) return;
    var ov = ensureOverlay();
    if (rtcVideoEl) screenEl.appendChild(rtcVideoEl); // video above the rrweb wrapper
    screenEl.appendChild(ov); // overlay last so it stays on top for input capture
    ov.style.display = "block";
    fitScreen();
    _dbg.attached = 1; hud();
  }

  // --- debug HUD: off by default; enable with ?hud=1 or #hud in the share URL to
  // diagnose control/video. The cheap _dbg bookkeeping stays so it can light up
  // on demand without code changes. ---
  var HUD_ENABLED = /(?:[?&]hud=1)|(?:#.*\bhud\b)/.test(location.search + location.hash);
  var _dbg = { grant: false, attached: 0, evt: "—", sent: 0, wsOpen: false, rtc: "off", vid: "no" };
  var _hudEl = null;
  function hud() {
    if (!HUD_ENABLED) return;
    if (!_hudEl) {
      _hudEl = document.createElement("div");
      _hudEl.style.cssText = "position:fixed;left:6px;bottom:6px;z-index:99999;background:rgba(0,0,0,.8);color:#0f0;font:11px monospace;padding:4px 7px;border-radius:4px;pointer-events:none;white-space:pre";
      document.body.appendChild(_hudEl);
    }
    _hudEl.textContent = "vid=" + _dbg.vid + "\n" +
      "ctrl=" + _dbg.grant + "  ws=" + _dbg.wsOpen + "  rtc=" + _dbg.rtc +
      "  listeners=" + _dbg.attached + "\nlastEvt=" + _dbg.evt + "  sent=" + _dbg.sent;
  }

  // --- send (encrypted or plaintext) ---
  function send(obj) {
    // High-frequency input rides the unreliable RTC channel when open: no relay
    // hop, no TCP head-of-line blocking (a dropped move is skipped, not retransmitted).
    if (rtcInput && inputDc && inputDc.readyState === "open" &&
        (obj.t === "ptr" || obj.t === "whl" || obj.t === "keyn")) {
      try { inputDc.send(JSON.stringify(obj)); _dbg.sent++; hud(); return; } catch (e) {}
    }
    if (!ws || ws.readyState !== 1) { _dbg.wsOpen = false; hud(); return; }
    _dbg.wsOpen = true; _dbg.sent++; hud();
    var text = JSON.stringify(obj);
    if (useE2E && encKey) { encryptFrame(text).then(function (buf) { try { ws.send(buf); } catch (e) {} }); }
    else { try { ws.send(text); } catch (e) {} }
  }

  // --- tab bar ---
  function renderTabs() {
    var bar = $("tabbar"); bar.innerHTML = "";
    tabs.forEach(function (t) {
      var el = document.createElement("div");
      el.className = "tab" + (t.tabId === activeTabId ? " active" : "");
      el.title = t.url || "";
      if (t.favicon) { var im = document.createElement("img"); im.className = "fav"; im.src = t.favicon; el.appendChild(im); }
      var ttl = document.createElement("span"); ttl.className = "ttl"; ttl.textContent = t.title || t.url || "Tab"; el.appendChild(ttl);
      el.onclick = function () { focusTab(t.tabId); };
      var x = document.createElement("span"); x.className = "x"; x.textContent = "×"; x.title = "Close tab";
      x.onclick = function (e) { e.stopPropagation(); closeTab(t.tabId); };
      el.appendChild(x);
      bar.appendChild(el);
    });
    var add = document.createElement("div");
    add.className = "newtab"; add.textContent = "+"; add.title = "New tab";
    add.onclick = function () { newTab(); };
    bar.appendChild(add);
  }
  function closeTab(id) {
    if (!controlGranted) { promptControl(); return; }
    send({ t: "closeTab", tabId: id });
  }
  function newTab() {
    if (!controlGranted) { promptControl(); return; }
    var u = prompt("New tab — enter a URL or search term:", "");
    if (u === null) return;          // cancelled
    u = u.trim();
    if (!u) return;                  // empty → no tab
    send({ t: "newTab", url: toUrl(u) });
  }
  // Mirror the host address bar: full URL as-is, a bare domain → https, else a Google search.
  function toUrl(s) {
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) return s;
    if (/^[^\s.]+\.[^\s]+$/.test(s)) return "https://" + s;
    return "https://www.google.com/search?q=" + encodeURIComponent(s);
  }
  function focusTab(id) {
    if (id === activeTabId) return;
    activeTabId = id; renderTabs();
    resetReplayer(); showStatus("Switching tab…");
    send({ t: "focusTab", tabId: id });
  }

  function setNav(t) {
    if (t.tabId !== activeTabId) return;
    var u = t.url || "";
    $("url").value = u;
    // BOSS-style security indicator: green lock for https pages.
    $("addrbar").classList.toggle("secure", /^https:\/\//i.test(u));
    $("back").disabled = !t.canGoBack;
    $("fwd").disabled = !t.canGoForward;
  }

  // --- control ---
  var ctlbtn = $("ctlbtn");
  function promptControl() {
    if (controlGranted) return;
    if (confirm("Request control of this browser? The host must approve.")) requestControl();
  }
  function requestControl() { send({ t: "requestControl" }); ctlbtn.textContent = "Control requested…"; }
  ctlbtn.onclick = function () { if (!controlGranted) requestControl(); };
  function setControl(granted) {
    controlGranted = granted;
    ctlbtn.textContent = granted ? "✓ In control" : "Request control";
    ctlbtn.classList.toggle("granted", granted);
    stage.classList.toggle("controlling", granted); // hide echoed host cursor while controlling
    _dbg.grant = granted; hud();
  }

  // nav buttons
  $("back").onclick = function () { if (controlGranted) send({ t: "back", tabId: activeTabId }); else promptControl(); };
  $("fwd").onclick = function () { if (controlGranted) send({ t: "forward", tabId: activeTabId }); else promptControl(); };
  $("reload").onclick = function () { if (controlGranted) send({ t: "reload", tabId: activeTabId }); else promptControl(); };
  $("url").addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      if (!controlGranted) { promptControl(); return; }
      var u = $("url").value.trim();
      if (u) send({ t: "navigate", tabId: activeTabId, url: /^[a-z]+:\/\//i.test(u) ? u : ("https://" + u) });
    }
  });

  // --- message dispatch ---
  function dispatch(m) {
    switch (m.t) {
      case "pending": showStatus("Waiting for the host to approve…"); break;
      case "grant": if (m.key) saveKey(m.key); break;
      case "denied": clearKey(); terminal = true; showStatus((m.reason || "Access denied") + ".", false); try { ws.close(); } catch (e) {} break;
      case "control": setControl(!!m.granted); break;
      case "presence": $("presence").textContent = m.viewers + (m.viewers === 1 ? " viewer" : " viewers"); break;
      case "layout": {
        tabs = m.tabs || [];
        var prevActive = activeTabId;
        var stillThere = tabs.some(function (t) { return t.tabId === activeTabId; });
        if (!activeTabId || !stillThere) activeTabId = m.activeTabId || (tabs[0] && tabs[0].tabId) || null;
        renderTabs();
        // If the tab we were viewing went away (e.g. closed), re-stream the new active one.
        if (!stillThere && activeTabId && activeTabId !== prevActive) {
          resetReplayer(); showStatus("Switching tab…"); send({ t: "focusTab", tabId: activeTabId });
        }
        break;
      }
      case "navStatus": setNav(m); for (var i = 0; i < tabs.length; i++) if (tabs[i].tabId === m.tabId) { tabs[i].title = m.title; tabs[i].url = m.url; tabs[i].canGoBack = m.canGoBack; tabs[i].canGoForward = m.canGoForward; tabs[i].favicon = m.favicon; tabs[i].loading = m.loading; } renderTabs(); break;
      case "domFocusAck": resetReplayer(); showStatus("Loading…"); break;
      case "domSnapshot": case "domMutation":
        try { feedEvent(JSON.parse(m.event)); } catch (e) {}
        break;
      // --- WebRTC signaling ---
      case "rtcConfig": if (m.enabled !== false && window.RTCPeerConnection) startRtc(m.iceServers || []); break;
      case "rtcAnswer": if (pc) pc.setRemoteDescription({ type: "answer", sdp: m.sdp }).catch(function () {}); break;
      case "rtcIce": if (pc && m.candidate) { try { pc.addIceCandidate(JSON.parse(m.candidate)); } catch (e) {} } break;
    }
  }

  // --- WebRTC peer (viewer is the offerer; creates the data channels) ---
  function startRtc(servers) {
    if (pc) return;
    try {
      pc = new RTCPeerConnection({ iceServers: servers });
      // input: unreliable + unordered (latest pointer wins; no retransmit stalls).
      inputDc = pc.createDataChannel("input", { ordered: false, maxRetransmits: 0 });
      // dom: reliable + ordered (rrweb snapshot/mutations must arrive intact, in order).
      domDc = pc.createDataChannel("dom", { ordered: true });
      inputDc.onopen = function () { rtcInput = true; _dbg.rtc = "input"; hud(); };
      inputDc.onclose = function () { rtcInput = false; _dbg.rtc = "off"; hud(); };
      // DOM frames are plaintext ServerMessage JSON (DTLS-encrypted on the wire).
      // Large frames arrive chunked ('C<id>:<i>:<n>:<data>'); reassemble them.
      domDc.onmessage = function (e) {
        var data = e.data;
        if (typeof data !== "string") return;
        if (data.charCodeAt(0) === 67 /* 'C' */) {
          var a = data.indexOf(":"), b2 = data.indexOf(":", a + 1), c = data.indexOf(":", b2 + 1);
          if (a < 0 || b2 < 0 || c < 0) return;
          var id = data.substring(1, a), i = +data.substring(a + 1, b2), n = +data.substring(b2 + 1, c), d = data.substring(c + 1);
          var buf = _domChunks[id] || (_domChunks[id] = { parts: new Array(n), got: 0 });
          if (buf.parts[i] === undefined) { buf.parts[i] = d; buf.got++; }
          if (buf.got === n) { var full = buf.parts.join(""); delete _domChunks[id]; try { dispatch(JSON.parse(full)); } catch (_) {} }
          return;
        }
        try { dispatch(JSON.parse(data)); } catch (_) {}
      };
      domDc.onopen = function () { _dbg.rtc = (rtcInput ? "input+dom" : "dom"); hud(); };
      // Receive the host's tab-pixel video track (true fidelity for video/canvas).
      pc.addTransceiver("video", { direction: "recvonly" });
      pc.ontrack = function (ev) {
        _dbg.vid = "ontrack:" + (ev.track && ev.track.kind); hud();
        if (ev.track && ev.track.kind === "video") showVideo(ev.streams[0] || new MediaStream([ev.track]));
      };
      pc.onicecandidate = function (ev) {
        if (ev.candidate) send({ t: "rtcIce", candidate: JSON.stringify(ev.candidate) }); // signaling → WS
      };
      pc.onconnectionstatechange = function () {
        var s = pc.connectionState;
        if (s === "failed" || s === "closed" || s === "disconnected") { rtcInput = false; }
      };
      pc.createOffer().then(function (offer) {
        return pc.setLocalDescription(offer).then(function () {
          send({ t: "rtcOffer", sdp: offer.sdp }); // signaling → WS (E2E-encrypted)
        });
      }).catch(function () {});
      // Give-up timer: if no data channel opens (blocked NAT, no TURN), drop the
      // peer and keep running on the WS path — input/DOM already default to it.
      setTimeout(function () {
        if (!rtcInput && pc && pc.connectionState !== "connected") { teardownRtc(); }
      }, 6000);
    } catch (e) { pc = null; }
  }

  function teardownRtc() {
    rtcInput = false; _dbg.rtc = "off"; hud();
    hideVideo();
    try { if (inputDc) inputDc.close(); } catch (_) {}
    try { if (domDc) domDc.close(); } catch (_) {}
    try { if (pc) pc.close(); } catch (_) {}
    pc = null; inputDc = null; domDc = null;
  }

  // --- video track rendering (host tab pixels; overlays the DOM mirror) ---
  // The rrweb iframe stays underneath, used only for input-coordinate mapping
  // (toHost). The video is positioned to exactly cover the iframe so clicks line up.
  function ensureVideoEl() {
    if (rtcVideoEl) return rtcVideoEl;
    var v = document.createElement("video");
    v.autoplay = true; v.muted = true; v.playsInline = true;
    // Cover the whole #screen frame (which is sized to the host viewport); it scales
    // together with the rrweb iframe under the same transform, so clicks stay aligned.
    v.style.cssText = "position:absolute;inset:0;width:100%;height:100%;z-index:45;background:#000;object-fit:fill;pointer-events:none;display:none;";
    v.addEventListener("loadedmetadata", function () {
      _dbg.vid = "play " + v.videoWidth + "x" + v.videoHeight; hud();
      fitScreen();
      var p = v.play(); if (p && p.catch) p.catch(function (e) { _dbg.vid = "play-blocked"; hud(); });
    });
    screenEl.appendChild(v);
    rtcVideoEl = v;
    return v;
  }
  function showVideo(stream) {
    var v = ensureVideoEl();
    try { v.srcObject = stream; } catch (e) { _dbg.vid = "srcObject-fail"; hud(); return; }
    v.style.display = "block";
    if (captureOverlay) screenEl.appendChild(captureOverlay); // keep input overlay above the video
    fitScreen();
    var p = v.play(); if (p && p.catch) p.catch(function () {});
    _dbg.vid = "shown"; hud();
  }
  function hideVideo() {
    if (rtcVideoEl) { try { rtcVideoEl.srcObject = null; } catch (_) {} rtcVideoEl.style.display = "none"; }
    _dbg.vid = "no"; hud();
  }
  // DevTools-style fit: size the #screen frame to the host viewport and scale it down
  // (never up) to fit the stage, centered. The rrweb iframe + video + overlay all live
  // inside #screen, so one transform scales them together and click mapping (toHost)
  // stays correct via getBoundingClientRect vs offsetWidth.
  function fitScreen() {
    var ifr = replayer && replayer.iframe;
    if (!ifr) return;
    var hw = ifr.offsetWidth || Math.round(ifr.getBoundingClientRect().width);
    var hh = ifr.offsetHeight || Math.round(ifr.getBoundingClientRect().height);
    if (!hw || !hh) return;
    screenEl.style.width = hw + "px";
    screenEl.style.height = hh + "px";
    var pad = 20;
    var availW = Math.max(50, stage.clientWidth - pad * 2);
    var availH = Math.max(50, stage.clientHeight - pad * 2);
    var s = Math.min(availW / hw, availH / hh, 1);
    if (!(s > 0)) s = 1;
    screenEl.style.transform = "scale(" + s + ")";
  }

  // --- connection (auto-reconnects with backoff; cached grant key re-admits
  // without re-prompting the host within the 24h grant window) ---
  var reconnectDelay = 0, reconnectTimer = null;
  function scheduleReconnect() {
    if (terminal || reconnectTimer) return;
    reconnectDelay = Math.min(reconnectDelay ? reconnectDelay * 2 : 1500, 10000);
    reconnectTimer = setTimeout(function () { reconnectTimer = null; connect(); }, reconnectDelay);
  }
  function connect() {
    var scheme = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(scheme + "://" + location.host + "/ws/" + encodeURIComponent(token));
    ws.binaryType = "arraybuffer";
    ws.onopen = function () {
      reconnectDelay = 0; // reset backoff on a successful connect
      if (useE2E) {
        $("e2e").style.display = ""; $("e2ecode").textContent = fingerprint(b64urlToBytes(secretB64));
        saltC = crypto.getRandomValues(new Uint8Array(16));
        phase = "kex";
        ws.send(JSON.stringify({ v: 1, salt: bytesToB64url(saltC) }));
        showStatus("Securing connection…");
      } else {
        phase = "stream";
        sendHello();
        showStatus("Connecting…");
      }
    };
    ws.onmessage = function (ev) {
      recvChain = recvChain.then(function () { return onFrame(ev.data); }).catch(function (e) { console.warn(e); });
    };
    ws.onclose = function () {
      teardownRtc();
      if (terminal) return;
      showStatus("Reconnecting…");
      scheduleReconnect();
    };
    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  }

  async function onFrame(data) {
    if (phase === "kex") {
      // Expect the server's plaintext Kex reply (text).
      var txt = (typeof data === "string") ? data : new TextDecoder().decode(data);
      var kex = JSON.parse(txt);
      var saltS = b64urlToBytes(kex.salt);
      var keys = await deriveAll(b64urlToBytes(secretB64), saltC, saltS);
      if (!bytesEqual(keys.confirm, b64urlToBytes(kex.confirm || ""))) { showStatus("Key mismatch — refusing (possible tampering).", false); try { ws.close(); } catch (e) {} return; }
      encKey = await importGcm(keys.kC2s, "encrypt");
      decKey = await importGcm(keys.kS2c, "decrypt");
      phase = "stream";
      await sendHelloAsync();
      return;
    }
    // stream phase
    var text;
    if (useE2E) { if (typeof data === "string") return; text = await decryptFrame(data); }
    else { text = (typeof data === "string") ? data : new TextDecoder().decode(data); }
    var m; try { m = JSON.parse(text); } catch (e) { return; }
    dispatch(m);
  }

  function helloObj() {
    var o = { t: "hello", clientId: clientId, name: navigator.platform || "Browser" };
    var k = loadKey(); if (k) o.key = k;
    return o;
  }
  function sendHello() { try { ws.send(JSON.stringify(helloObj())); } catch (e) {} }
  async function sendHelloAsync() { try { ws.send(await encryptFrame(JSON.stringify(helloObj()))); } catch (e) {} }

  function fingerprint(bytes) {
    // first 4 bytes of SHA-256, hex — matches BrowserSessionCrypto.fingerprint (async best-effort)
    if (!subtle) return "";
    subtle.digest("SHA-256", bytes).then(function (h) {
      var a = new Uint8Array(h).slice(0, 4), s = "";
      for (var i = 0; i < a.length; i++) s += ("0" + a[i].toString(16)).slice(-2);
      $("e2ecode").textContent = s;
    });
    return "…";
  }

  if (!token) { showStatus("Missing share token.", false); return; }
  window.addEventListener("resize", fitScreen);
  connect();
})();
