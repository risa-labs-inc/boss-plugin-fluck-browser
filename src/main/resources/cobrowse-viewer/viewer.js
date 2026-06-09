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

  // --- rrweb replayer (live) ---
  var replayer = null, building = false, evBuffer = [];
  var stage = $("stage");
  function destroyReplayer() {
    if (replayer) { try { replayer.pause(); } catch (e) {} }
    // remove any prior rrweb iframe/wrapper
    Array.prototype.slice.call(stage.querySelectorAll(".replayer-wrapper, iframe")).forEach(function (n) { n.remove(); });
    replayer = null; building = false; evBuffer = [];
  }
  function resetReplayer() { destroyReplayer(); building = true; }
  function feedEvent(ev) {
    if (building) {
      evBuffer.push(ev);
      if (ev.type === 2) { // FullSnapshot present -> safe to construct
        try {
          replayer = new rrweb.Replayer(evBuffer, { liveMode: true, root: stage, mouseTail: false, UNSAFE_replayCanvas: false });
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

  // --- control capture (inside the replayer iframe) ---
  function attachControlListeners() {
    if (!replayer || !replayer.iframe) return;
    var idoc = replayer.iframe.contentDocument;
    if (!idoc || idoc.__bossCtl) return; // bind once per (re-opened) document
    idoc.__bossCtl = true;
    function mirrorId(node) { try { return replayer.getMirror().getId(node); } catch (e) { return -1; } }
    idoc.addEventListener("click", function (e) {
      if (!controlGranted) { e.preventDefault(); e.stopPropagation(); promptControl(); return; }
      var id = mirrorId(e.target);
      if (id >= 0) send({ t: "click", tabId: activeTabId, id: id });
      e.preventDefault(); e.stopPropagation();
    }, true);
    idoc.addEventListener("input", function (e) {
      if (!controlGranted) return;
      var id = mirrorId(e.target);
      if (id >= 0) send({ t: "input", tabId: activeTabId, id: id, value: (e.target.value != null ? e.target.value : (e.target.textContent || "")) });
    }, true);
    idoc.addEventListener("keydown", function (e) {
      if (!controlGranted) return;
      var id = mirrorId(e.target);
      if (id >= 0) send({ t: "key", tabId: activeTabId, id: id, key: e.key || "", code: e.code || "" });
    }, true);
    var scrollT = 0;
    idoc.addEventListener("scroll", function (e) {
      if (!controlGranted) return;
      var now = Date.now();
      if (now - scrollT < 80) return; scrollT = now;
      var tgt = (e.target === idoc || e.target === idoc.documentElement) ? (idoc.scrollingElement || idoc.documentElement) : e.target;
      var id = mirrorId(tgt);
      var x = (tgt.scrollLeft != null ? tgt.scrollLeft : 0), y = (tgt.scrollTop != null ? tgt.scrollTop : 0);
      send({ t: "scroll", tabId: activeTabId, id: id >= 0 ? id : null, x: Math.round(x), y: Math.round(y) });
    }, true);
  }

  // --- send (encrypted or plaintext) ---
  function send(obj) {
    if (!ws || ws.readyState !== 1) return;
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
      bar.appendChild(el);
    });
  }
  function focusTab(id) {
    if (id === activeTabId) return;
    activeTabId = id; renderTabs();
    resetReplayer(); showStatus("Switching tab…");
    send({ t: "focusTab", tabId: id });
  }

  function setNav(t) {
    if (t.tabId !== activeTabId) return;
    $("url").value = t.url || "";
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
      case "layout":
        tabs = m.tabs || [];
        if (!activeTabId || !tabs.some(function (t) { return t.tabId === activeTabId; })) activeTabId = m.activeTabId || (tabs[0] && tabs[0].tabId) || null;
        renderTabs();
        break;
      case "navStatus": setNav(m); for (var i = 0; i < tabs.length; i++) if (tabs[i].tabId === m.tabId) { tabs[i].title = m.title; tabs[i].url = m.url; tabs[i].canGoBack = m.canGoBack; tabs[i].canGoForward = m.canGoForward; tabs[i].favicon = m.favicon; tabs[i].loading = m.loading; } renderTabs(); break;
      case "domFocusAck": resetReplayer(); showStatus("Loading…"); break;
      case "domSnapshot": case "domMutation":
        try { feedEvent(JSON.parse(m.event)); } catch (e) {}
        break;
    }
  }

  // --- connection ---
  function connect() {
    var scheme = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(scheme + "://" + location.host + "/ws/" + encodeURIComponent(token));
    ws.binaryType = "arraybuffer";
    ws.onopen = function () {
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
    ws.onclose = function () { if (terminal) return; showStatus("Disconnected.", false); };
    ws.onerror = function () { showStatus("Connection error.", false); };
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
  connect();
})();
