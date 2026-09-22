/* T2V·LAB — front controller */
(() => {
  "use strict";

  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const api = async (path, opts = {}) => {
    const r = await fetch(path, opts.method && opts.method !== "GET"
      ? { method: opts.method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(opts.body || {}) }
      : undefined);
    if (!r.ok) {
      let msg = `${r.status}`;
      try { msg = (await r.json()).detail || msg; } catch (e) { /* noop */ }
      throw new Error(msg);
    }
    return r.json();
  };

  const state = {
    cfg: null,
    sel: { scene: "auto", motion: "auto", aspect: "16:9", quality: "draft", duration_s: 5, letterbox: false, engine: "local" },
    prompt: localStorage.getItem("t2v.prompt") || "",
    enhance: localStorage.getItem("t2v.enhance") === "1",
    jobs: new Map(),
    gallery: [],
    galSig: "",
  };

  // ------------------------------------------------------------ toasts
  const toast = (msg, isErr = false) => {
    const t = document.createElement("div");
    t.className = "toast" + (isErr ? " err" : "");
    t.textContent = msg;
    $(".toasts").appendChild(t);
    setTimeout(() => { t.style.opacity = "0"; t.style.transition = "opacity .4s"; }, 4200);
    setTimeout(() => t.remove(), 4700);
  };

  // ------------------------------------------------------------ placeholders
  let phTimer = null;
  const cyclePlaceholder = () => {
    const ideas = state.cfg?.prompt_ideas || [];
    if (!ideas.length) return;
    let i = Math.floor(Math.random() * ideas.length);
    const el = $("#prompt");
    clearInterval(phTimer);
    phTimer = setInterval(() => {
      if (document.activeElement === el || el.value) return;
      i = (i + 1) % ideas.length;
      el.placeholder = ideas[i] + "…";
    }, 3400);
    el.placeholder = ideas[i] + "…";
  };

  // ------------------------------------------------------------ controls
  const pill = (label, on, tip, ghost) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "pillbtn" + (on ? " on" : "") + (ghost ? " ghost" : "");
    b.textContent = label;
    if (tip) b.dataset.tip = tip;
    return b;
  };
  const seg = (opts, cur, cb) => {
    const wrap = document.createElement("div");
    wrap.className = "seg";
    opts.forEach(([val, lab]) => {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = lab;
      if (val === cur) b.classList.add("on");
      b.onclick = () => { $$(".on", wrap).forEach(x => x.classList.remove("on")); b.classList.add("on"); cb(val); };
      wrap.appendChild(b);
    });
    return wrap;
  };
  const restored = (k) => localStorage.getItem("t2v." + k);

  function buildControls() {
    const c = state.cfg;
    const saved = (k, d) => (restored(k) ?? d);

    // engine
    const engines = [["local", "Local Engine — instant"]];
    const st = c.cloud || {};
    engines.push(["replicate", st.replicate?.has_key ? "Replicate · AI model" : "Replicate · add key ⚙"]);
    engines.push(["fal", st.fal?.has_key ? "fal.ai · AI model" : "fal.ai · add key ⚙"]);
    const eng = $("#ctl-engine .pills");
    eng.innerHTML = "";
    state.sel.engine = saved("engine", "local");
    if (state.sel.engine !== "local" && !st[state.sel.engine]?.has_key) state.sel.engine = "local";
    engines.forEach(([v, lab]) => {
      const needsKey = v !== "local" && !st[v]?.has_key;
      const b = pill(lab, v === state.sel.engine, needsKey ? "save an API token in ⚙ settings" : null, needsKey);
      b.onclick = () => {
        if (needsKey) { openSettings(); return; }
        state.sel.engine = v; localStorage.setItem("t2v.engine", v);
        $$(".on", eng).forEach(x => x.classList.remove("on")); b.classList.add("on");
      };
      eng.appendChild(b);
    });

    // world (scene)
    state.sel.scene = saved("scene", "auto");
    const sc = $("#ctl-scene .pills");
    sc.innerHTML = "";
    const autoB = pill("✦ Auto — director picks", state.sel.scene === "auto", "prompt keywords choose the world");
    autoB.onclick = () => { state.sel.scene = "auto"; localStorage.setItem("t2v.scene", "auto"); $$(".on", sc).forEach(x => x.classList.remove("on")); autoB.classList.add("on"); };
    sc.appendChild(autoB);
    c.scenes.forEach(s => {
      const b = pill(s.label, s.id === state.sel.scene, `default motion: ${s.default_motion}`);
      b.onclick = () => { state.sel.scene = s.id; localStorage.setItem("t2v.scene", s.id); $$(".on", sc).forEach(x => x.classList.remove("on")); b.classList.add("on"); };
      sc.appendChild(b);
    });

    // motion
    state.sel.motion = saved("motion", "auto");
    const mo = $("#ctl-motion .pills");
    mo.innerHTML = "";
    c.motions.forEach(m => {
      const b = pill(m.label, m.id === state.sel.motion, m.desc);
      b.onclick = () => { state.sel.motion = m.id; localStorage.setItem("t2v.motion", m.id); $$(".on", mo).forEach(x => x.classList.remove("on")); b.classList.add("on"); };
      mo.appendChild(b);
    });

    // segmented rows
    state.sel.aspect = saved("aspect", "16:9");
    $("#ctl-aspect").replaceChildren(seg(
      [["16:9", "16:9 widescreen"], ["9:16", "9:16 vertical"], ["1:1", "1:1 square"]],
      state.sel.aspect, v => { state.sel.aspect = v; localStorage.setItem("t2v.aspect", v); }));

    state.sel.duration_s = +saved("duration_s", 5);
    $("#ctl-duration").replaceChildren(seg(
      c.durations.map(d => [d, d + "s"]),
      state.sel.duration_s, v => { state.sel.duration_s = v; localStorage.setItem("t2v.duration_s", v); }));

    state.sel.quality = saved("quality", "draft");
    const qlabels = { draft: "Draft · fastest", standard: "Standard", high: "HD 720p · slower" };
    $("#ctl-quality").replaceChildren(seg(
      c.qualities.map(q => [q, qlabels[q] || q]),
      state.sel.quality, v => { state.sel.quality = v; localStorage.setItem("t2v.quality", v); }));

    // letterbox toggle
    state.sel.letterbox = restored("letterbox") === "1";
    const tog = $("#ctl-letterbox");
    const paintTog = () => tog.classList.toggle("on", state.sel.letterbox);
    paintTog();
    tog.onclick = () => { state.sel.letterbox = !state.sel.letterbox; localStorage.setItem("t2v.letterbox", state.sel.letterbox ? "1" : "0"); paintTog(); };

    cyclePlaceholder();
  }

  // ------------------------------------------------------------ enhance
  async function refreshEnhanced(showMsg) {
    const p = $("#prompt").value.trim();
    const box = $("#enhanced");
    if (!state.enhance || p.length < 2) { box.classList.remove("show"); return; }
    try {
      const r = await api("/api/enhance", { method: "POST", body: { prompt: p } });
      box.innerHTML = `<b>✦ director's cut:</b> ${escapeHtml(r.enhanced)}`;
      box.classList.add("show");
      if (showMsg) toast("Prompt enhanced");
    } catch (e) { /* ignore transient */ }
  }
  const escapeHtml = (s) => s.replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));

  // ------------------------------------------------------------ generate
  async function generate() {
    const prompt = $("#prompt").value.trim() || $("#prompt").placeholder.replace(/…$/, "");
    if (prompt.length < 2) { toast("Type a prompt first", true); return; }
    localStorage.setItem("t2v.prompt", $("#prompt").value);
    const body = {
      prompt,
      scene: state.sel.scene === "auto" ? null : state.sel.scene,
      motion: state.sel.motion,
      aspect: state.sel.aspect,
      quality: state.sel.quality,
      duration_s: state.sel.duration_s,
      letterbox: state.sel.letterbox,
      seed: $("#seed").value ? +$("#seed").value : null,
      engine: state.sel.engine,
    };
    $("#genBtn").disabled = true;
    try {
      const url = state.sel.engine === "local" ? "/api/generate" : "/api/generate_cloud";
      await api(url, { method: "POST", body });
      $("#seed").value = "";
      pollNow();
      toast(state.sel.engine === "local" ? "Queued — rendering your shot" : "Sent to " + state.sel.engine + " — this takes a few minutes");
    } catch (e) {
      toast(e.message || "failed to queue", true);
    } finally {
      $("#genBtn").disabled = false;
    }
  }

  // ------------------------------------------------------------ job polling
  let pollT = null;
  async function pollNow() {
    try {
      const jobs = await api("/api/jobs");
      renderQueue(jobs.filter(j => j.status === "queued" || j.status === "running"));
      const done = jobs.filter(j => j.status === "done");
      const errs = jobs.filter(j => j.status === "error");
      renderGallery([...done, ...errs]);
      const active = jobs.some(j => j.status === "queued" || j.status === "running");
      clearTimeout(pollT);
      pollT = setTimeout(pollNow, active ? 1100 : 5000);
    } catch (e) {
      clearTimeout(pollT);
      pollT = setTimeout(pollNow, 8000);
    }
  }

  function renderQueue(active) {
    const q = $("#queue");
    q.innerHTML = "";
    active.forEach(j => {
      const pct = j.status === "queued" ? 0 : Math.round((j.progress || 0) * 100);
      const card = document.createElement("div");
      card.className = "job-card";
      card.innerHTML = `
        <div style="font-size:22px">${j.status === "queued" ? "⏳" : "🎬"}</div>
        <div class="job-prompt" title="${escapeHtml(j.prompt)}">
          ${escapeHtml(j.prompt)}
          <div class="job-stage">${j.status === "queued" ? "in queue…" : `directing → rendering frames → encoding mp4 (${j.params?.quality || j.params?.engine || ""})`}</div>
        </div>
        <div class="job-bar">
          <div class="bar-track"><div class="bar-fill" style="width:${j.status === "queued" ? 4 : Math.max(4, pct)}%"></div></div>
          <div class="job-pct">${j.status === "queued" ? "queued" : pct + "%"}</div>
        </div>`;
      q.appendChild(card);
    });
  }

  // ------------------------------------------------------------ gallery
  function renderGallery(items) {
    const g = $("#grid");
    const sig = items.map(j => j.id + j.status).join("|");
    if (sig === state.galSig) return;          // no change — keep hover videos alive
    state.galSig = sig;
    g.innerHTML = "";
    $("#galCount").textContent = items.length ? `${items.length} clip${items.length > 1 ? "s" : ""}` : "";
    if (!items.length) {
      g.innerHTML = `<div class="empty"><b>Your gallery is empty</b>Describe a shot above — the local engine renders it in seconds.</div>`;
      return;
    }
    items.forEach(j => {
      const meta = j.meta || {};
      const card = document.createElement("div");
      const ratioCls = j.params?.aspect === "9:16" ? "ratio-tall" : j.params?.aspect === "1:1" ? "ratio-sq" : "";
      card.className = "card " + ratioCls;
      if (j.status === "error") {
        card.innerHTML = `
          <div class="meta" style="padding:16px">
            <div class="prompt">⚠ ${escapeHtml(j.prompt)}</div>
            <div class="tags"><span class="tag" style="color:var(--err)">${escapeHtml((j.error || "failed").slice(0, 80))}</span></div>
          </div>`;
        card.onclick = () => { if (confirm("Delete this failed job?")) delJob(j.id); };
        g.appendChild(card);
        return;
      }
      const thumb = j.thumb_url ? `<img class="thumb" src="${j.thumb_url}" loading="lazy" alt="">`
        : `<div class="thumb" style="background:linear-gradient(120deg,#1b1d33,#3a1f4d)"></div>`;
      card.innerHTML = `
        <div class="thumbwrap">
          ${thumb}
          <video muted loop playsinline preload="none" ${j.thumb_url ? `poster="${j.thumb_url}"` : ""}></video>
          <div class="play-badge">▶</div>
        </div>
        <div class="meta">
          <div class="prompt">${escapeHtml(j.prompt)}</div>
          <div class="tags">
            ${meta.scene_label ? `<span class="tag scene">${escapeHtml(meta.scene_label)}</span>` : ""}
            ${meta.motion_label ? `<span class="tag">${escapeHtml(meta.motion_label)}</span>` : ""}
            ${meta.duration_s ? `<span class="tag">${meta.duration_s}s</span>` : ""}
            ${meta.w ? `<span class="tag">${meta.w}×${meta.h}</span>` : ""}
            ${meta.seed != null ? `<span class="tag">#${meta.seed}</span>` : ""}
          </div>
        </div>`;
      const v = $("video", card);
      card.addEventListener("mouseenter", () => { if (!v.src) v.src = j.video_url; v.play().catch(() => {}); });
      card.addEventListener("mouseleave", () => { v.pause(); });
      card.addEventListener("click", (e) => { e.preventDefault(); openLightbox(j); });
      g.appendChild(card);
    });
  }

  // ------------------------------------------------------------ lightbox
  function openLightbox(j) {
    const lb = $("#lightbox");
    const meta = j.meta || {};
    $("#lbVideo").src = j.video_url;
    if (j.thumb_url) $("#lbVideo").poster = j.thumb_url;
    $("#lbPrompt").textContent = j.prompt;
    $("#lbEnh").textContent = meta.enhanced ? `✦ ${meta.enhanced}` : "";
    $("#lbTags").innerHTML = [
      meta.scene_label && `<span class="tag scene">${escapeHtml(meta.scene_label)}</span>`,
      meta.motion_label && `<span class="tag">${escapeHtml(meta.motion_label)}</span>`,
      meta.w && `<span class="tag">${meta.w}×${meta.h} · ${meta.fps}fps</span>`,
      meta.duration_s && `<span class="tag">${meta.duration_s}s</span>`,
      meta.energy != null && `<span class="tag">energy ${meta.energy}</span>`,
      meta.seed != null && `<span class="tag">seed ${meta.seed}</span>`,
    ].filter(Boolean).join("");
    $("#lbDownload").href = j.video_url;
    $("#lbDownload").setAttribute("download", `t2v-${j.id}.mp4`);
    $("#lbRemix").onclick = () => {
      $("#prompt").value = j.prompt;
      localStorage.setItem("t2v.prompt", j.prompt);
      if (meta.seed != null) $("#seed").value = meta.seed;
      if (j.params?.scene) state.sel.scene = j.params.scene;
      closeLightbox();
      window.scrollTo({ top: 0, behavior: "smooth" });
      refreshEnhanced(false);
      toast("Loaded into composer — tweak and regenerate");
    };
    $("#lbDelete").onclick = () => delJob(j.id, true);
    lb.classList.add("show");
    $("#lbVideo").play().catch(() => {});
    document.body.style.overflow = "hidden";
  }
  function closeLightbox() {
    const v = $("#lbVideo");
    v.pause(); v.removeAttribute("src"); v.load();
    $("#lightbox").classList.remove("show");
    document.body.style.overflow = "";
  }

  async function delJob(id, close) {
    try {
      await api(`/api/jobs/${id}`, { method: "DELETE" });
      if (close) closeLightbox();
      pollNow();
      toast("Deleted");
    } catch (e) { toast(e.message, true); }
  }

  // ------------------------------------------------------------ settings
  async function openSettings() {
    try {
      const s = await api("/api/settings");
      $("#setStatus").innerHTML =
        `${s.has_replicate ? "✓ replicate token saved" : "○ no replicate token"}<br>` +
        `${s.has_fal ? "✓ fal key saved" : "○ no fal key"}`;
    } catch (e) { /* ignore */ }
    $("#settings").classList.add("show");
    document.body.style.overflow = "hidden";
  }
  function closeSettings() {
    $("#settings").classList.remove("show");
    document.body.style.overflow = "";
  }
  async function saveSettings() {
    const body = {
      replicate_token: $("#repTok").value || "",
      fal_key: $("#falTok").value || "",
    };
    try {
      await api("/api/settings", { method: "POST", body });
      toast("Settings saved");
      closeSettings();
      state.cfg = await api("/api/config");
      buildControls();
    } catch (e) { toast(e.message, true); }
  }

  // ------------------------------------------------------------ wiring
  function wire() {
    $("#genBtn").onclick = generate;
    $("#prompt").addEventListener("keydown", (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "Enter") generate();
    });
    let et = null;
    $("#prompt").addEventListener("input", () => {
      clearTimeout(et);
      et = setTimeout(() => refreshEnhanced(false), 650);
    });
    $("#dice").onclick = () => {
      const ideas = state.cfg?.prompt_ideas || [];
      if (!ideas.length) return;
      $("#prompt").value = ideas[Math.floor(Math.random() * ideas.length)];
      refreshEnhanced(false);
      $("#prompt").focus();
    };
    const spk = $("#sparkle");
    const paintSpark = () => spk.classList.toggle("on", state.enhance);
    paintSpark();
    spk.onclick = () => {
      state.enhance = !state.enhance;
      localStorage.setItem("t2v.enhance", state.enhance ? "1" : "0");
      paintSpark();
      refreshEnhanced(true);
    };
    $("#seedRand").onclick = () => { $("#seed").value = Math.floor(Math.random() * 2 ** 31); };
    $("#settingsBtn").onclick = openSettings;
    $("#saveSettings").onclick = saveSettings;
    $("#closeSettings").onclick = closeSettings;
    $("#lightbox .close-x").onclick = closeLightbox;
    $("#lightbox").addEventListener("click", (e) => { if (e.target.id === "lightbox") closeLightbox(); });
    $("#settings").addEventListener("click", (e) => { if (e.target.id === "settings") closeSettings(); });
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") { closeLightbox(); closeSettings(); }
    });
    $("#prompt").value = state.prompt;
    refreshEnhanced(false);
  }

  // ------------------------------------------------------------ boot
  (async () => {
    try {
      state.cfg = await api("/api/config");
      if (!state.cfg.cloud.replicate.has_key && !state.cfg.cloud.fal.has_key) {
        $("#cloudPill").classList.add("warn");
        $("#cloudPillText").textContent = "AI models locked · add key ⚙";
      } else if (!state.cfg.cloud.replicate.reachable && !state.cfg.cloud.fal.reachable) {
        $("#cloudPill").classList.add("warn");
        $("#cloudPillText").textContent = "AI models unreachable from server";
      }
    } catch (e) {
      toast("config failed to load", true);
    }
    buildControls();
    wire();
    pollNow();
  })();
})();
