/* Lexi Reader rendering engine. Talks to Kotlin through window.Android (ReaderBridge).
 *
 * Layouts for reflowable books:
 *   paged  – one chapter at a time laid out in CSS columns, one column per screen; swipe/tap turns pages.
 *   scroll – continuous vertical scrolling with neighbouring chapters streamed in and out.
 * PDFs are rendered page by page with pdf.js (mode 'pdf').
 *
 * Book-wide page numbers come from laying every chapter out off-screen in the paged geometry; until a
 * chapter has been measured its page count is estimated from its size.
 */
(function () {
  'use strict';
  const A = window.Android;
  const content = document.getElementById('content');
  const overlays = document.getElementById('overlays');
  const measureBox = document.getElementById('measure');
  const R = (window.Reader = {});

  const MAX_SECTIONS = 5;
  const WORD_CH = /[\p{L}\p{M}\p{N}'’\-]/u;
  const EDGE_CH = /['’\-]/;
  const SECTION_SEL = 'section.chapter, .page';

  let mode = 'flow';          // 'flow' | 'pdf'
  let layout = 'scroll';      // 'scroll' | 'paged' (flow only)
  let count = 0;
  let topInset = 24, bottomInset = 24;
  let ready = false;
  let loadingNext = false, loadingPrev = false;
  let lastSel = null, selAtDown = false;
  let popupOpen = false, scrollNotified = false;
  let navToken = 0;
  let settings = {};
  let bookKey = '';
  let weights = [];

  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const nextFrame = () => new Promise(r => requestAnimationFrame(() => r()));
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const paged = () => mode === 'flow' && layout === 'paged';

  /** CSS vh can resolve to 0 inside Android WebView, so the real height is published as --vh. */
  function syncViewport() {
    document.documentElement.style.setProperty('--vh', innerHeight + 'px');
  }
  syncViewport();

  // ------------------------------------------------------------------ text offsets
  function textNodes(root) {
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    const out = [];
    let n;
    while ((n = w.nextNode())) out.push(n);
    return out;
  }

  function offsetOf(root, node, off) {
    const r = document.createRange();
    r.setStart(root, 0);
    r.setEnd(node, off);
    return r.toString().length;
  }

  function rangeAt(root, start, end) {
    const r = document.createRange();
    let pos = 0, started = false, last = null;
    for (const n of textNodes(root)) {
      const len = n.data.length;
      if (!started && start < pos + len) { r.setStart(n, start - pos); started = true; }
      if (started && end <= pos + len) { r.setEnd(n, Math.max(0, end - pos)); return r; }
      pos += len;
      last = n;
    }
    if (started && last) { r.setEnd(last, last.data.length); return r; }
    return null;
  }

  function wrapRange(range, make) {
    const nodes = [];
    const root = range.commonAncestorContainer;
    if (root.nodeType === 3) nodes.push(root);
    else for (const n of textNodes(root)) if (range.intersectsNode(n)) nodes.push(n);
    const sc = range.startContainer, so = range.startOffset, ec = range.endContainer, eo = range.endOffset;
    for (const n of nodes) {
      const s = n === sc ? so : 0;
      const e = n === ec ? eo : n.data.length;
      if (e <= s) continue;
      if (!n.data.slice(s, e).trim()) continue;
      let t = n;
      if (s > 0) t = t.splitText(s);
      if (e - s < t.data.length) t.splitText(e - s);
      const m = make();
      t.parentNode.insertBefore(m, t);
      m.appendChild(t);
    }
  }

  function clearMarks(root) {
    const marks = root.querySelectorAll('mark.hl');
    if (!marks.length) return;
    marks.forEach(m => {
      const p = m.parentNode;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
    });
    root.normalize();
  }

  function sectionOf(ch) {
    return content.querySelector('section.chapter[data-ch="' + ch + '"], .page[data-ch="' + ch + '"]');
  }

  function annRoot(ch) {
    const sec = sectionOf(ch);
    if (!sec) return null;
    if (mode === 'pdf') {
      const tl = sec.querySelector('.textLayer');
      return tl && tl.dataset.ready ? tl : null;
    }
    return sec;
  }

  const squash = t => t.replace(/\s+/g, ' ').trim();

  /**
   * Stored offsets go stale when the chapter text changes (parser/cleaner updates). If the annotation
   * carries its quote (`q`) and the text at [s, e) no longer matches, use the occurrence of the quote
   * nearest the old position; returns null when it can't be found so the wrong passage is never marked.
   */
  function anchorOf(a, full) {
    if (!a.q) return { s: a.s, e: a.e };
    if (squash(full.slice(a.s, a.e)) === squash(a.q)) return { s: a.s, e: a.e };
    const words = squash(a.q).split(' ').filter(Boolean);
    if (!words.length) return null;
    const re = new RegExp(words.map(w => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s+'), 'g');
    let best = null, m;
    while ((m = re.exec(full))) {
      if (!best || Math.abs(m.index - a.s) < Math.abs(best.s - a.s)) best = { s: m.index, e: m.index + m[0].length };
    }
    return best;
  }

  function applyAnnotations(ch) {
    const root = annRoot(ch);
    if (!root) return;
    clearMarks(root);
    let list = [];
    try { list = JSON.parse(A.getAnnotations(ch)); } catch (e) { list = []; }
    const full = root.textContent;
    list = list.map(a => { const at = anchorOf(a, full); return at ? Object.assign({}, a, at) : null; }).filter(Boolean);
    list.sort((a, b) => a.s - b.s);
    for (const a of list) {
      const r = rangeAt(root, a.s, a.e);
      if (!r) continue;
      wrapRange(r, () => {
        const m = document.createElement('mark');
        m.className = 'hl' + (a.c ? ' c-' + a.c : ' ul') + (a.n ? ' note' : '');
        m.dataset.id = a.id;
        if (a.u) m.style.setProperty('--u', a.u);
        return m;
      });
    }
  }

  // ------------------------------------------------------------------ overlays (tap mark, flash)
  /** Overlays live inside the scrolling element so they move with the text in both layouts. */
  function overlayOrigin() {
    if (paged()) {
      const r = content.getBoundingClientRect();
      return { x: content.scrollLeft - r.left, y: content.scrollTop - r.top };
    }
    return { x: scrollX, y: scrollY };
  }

  function drawRects(range, cls, keep) {
    const o = overlayOrigin();
    const els = [];
    for (const q of range.getClientRects()) {
      if (q.width < 1 || q.height < 1) continue;
      const d = document.createElement('div');
      d.className = 'ov ' + cls;
      d.style.left = (q.left + o.x - 1) + 'px';
      d.style.top = (q.top + o.y - 1) + 'px';
      d.style.width = (q.width + 2) + 'px';
      d.style.height = (q.height + 2) + 'px';
      overlays.appendChild(d);
      els.push(d);
    }
    if (!keep) setTimeout(() => els.forEach(e => e.remove()), 2300);
    return els;
  }

  let tapEls = [];
  function clearTap() { tapEls.forEach(e => e.remove()); tapEls = []; }
  R.clearTap = clearTap;

  function mountOverlays() {
    overlays.innerHTML = '';
    tapEls = [];
    if (paged()) content.appendChild(overlays);
    else document.body.appendChild(overlays);
  }

  // ------------------------------------------------------------------ search helpers
  function normText(raw) {
    let norm = '';
    const idx = [];
    let prevSpace = false;
    for (let i = 0; i < raw.length; i++) {
      const c = raw[i];
      if (/\s/.test(c)) {
        if (prevSpace) continue;
        norm += ' '; idx.push(i); prevSpace = true;
      } else {
        norm += c.toLowerCase(); idx.push(i); prevSpace = false;
      }
    }
    return { norm, idx };
  }

  function isBoundary(s, i) { return i < 0 || i >= s.length || !WORD_CH.test(s[i]); }

  function findAll(norm, q, whole) {
    const out = [];
    if (!q) return out;
    let from = 0;
    for (;;) {
      const p = norm.indexOf(q, from);
      if (p < 0) break;
      from = p + 1;
      if (whole && !(isBoundary(norm, p - 1) && isBoundary(norm, p + q.length))) continue;
      out.push(p);
    }
    return out;
  }

  function normQuery(q) { return q.replace(/\s+/g, ' ').trim().toLowerCase(); }

  function findNth(root, q, nth, whole) {
    let raw = '';
    for (const n of textNodes(root)) raw += n.data;
    const { norm, idx } = normText(raw);
    const nq = normQuery(q);
    const hits = findAll(norm, nq, whole);
    if (!hits.length) return null;
    const p = hits[clamp(nth || 0, 0, hits.length - 1)];
    return rangeAt(root, idx[p], idx[p + nq.length - 1] + 1);
  }

  // ------------------------------------------------------------------ word tapping
  function wordRangeAt(x, y) {
    const range = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
    if (!range) return null;
    const node = range.startContainer;
    if (!node || node.nodeType !== 3) return null;
    const text = node.data;
    let s = range.startOffset, e = range.startOffset;
    while (s > 0 && WORD_CH.test(text[s - 1])) s--;
    while (e < text.length && WORD_CH.test(text[e])) e++;
    while (s < e && EDGE_CH.test(text[s])) s++;
    while (e > s && EDGE_CH.test(text[e - 1])) e--;
    if (e <= s) return null;
    const r = document.createRange();
    r.setStart(node, s);
    r.setEnd(node, e);
    const slop = 8;
    for (const q of r.getClientRects()) {
      if (x >= q.left - slop && x <= q.right + slop && y >= q.top - slop && y <= q.bottom + slop) return r;
    }
    return null;
  }

  function chOfNode(node) {
    const el = node.nodeType === 3 ? node.parentElement : node;
    const sec = el && el.closest(SECTION_SEL);
    return sec ? +sec.dataset.ch : -1;
  }

  // ------------------------------------------------------------------ links & footnotes
  const NOTE_TEXT = /^[\[(]?(\d{1,3}|[*†‡§¶]|[a-z])[\])]?$/i;

  function isNoteRef(a) {
    const t = (a.getAttribute('epub:type') || '') + ' ' + (a.getAttribute('role') || '');
    if (/noteref/.test(t)) return true;
    return NOTE_TEXT.test(a.textContent.trim()) || !!a.closest('sup');
  }

  async function noteText(ch, anchor) {
    if (!anchor) return '';
    let el = null;
    const sec = sectionOf(ch);
    if (sec && mode === 'flow') el = sec.querySelector('[id="' + CSS.escape(anchor) + '"]');
    if (!el) {
      try {
        const doc = new DOMParser().parseFromString('<body>' + await getChapter(ch) + '</body>', 'text/html');
        el = doc.querySelector('[id="' + CSS.escape(anchor) + '"]');
      } catch (e) { el = null; }
    }
    if (!el) return '';
    let box = el;
    // Anchors are often empty markers inside the real note; climb to the enclosing block.
    while (box && box.textContent.trim().length < 2 && box.parentElement) box = box.parentElement;
    const block = box.closest && box.closest('aside, li, p, div, section, dd, td');
    const text = (block && block.textContent.trim().length < 2500 ? block : box).textContent;
    return text.replace(/\s+/g, ' ').trim().slice(0, 2500);
  }

  const SAFE_EXT = /^(https?:|mailto:)/i;

  async function followLink(a) {
    const ext = (a.dataset.ext || '').trim();
    if (ext) { if (SAFE_EXT.test(ext)) A.openExternal(ext); return; }
    const ch = +a.dataset.lch, anchor = a.dataset.lanchor || '';
    if (isNoteRef(a)) {
      const text = await noteText(ch, anchor);
      if (text && text.length > 1) {
        const q = a.getBoundingClientRect();
        A.onFootnote(text, ch, anchor, q.left, q.top, q.right, q.bottom);
        return;
      }
    }
    A.onInternalLink(ch, anchor);
  }

  let suppressClickUntil = 0;

  /** Initial-open position restore, re-applied once images have settled unless the user moves first. */
  let pendingRestore = null;

  function restoreStillWanted() {
    const r = pendingRestore;
    if (!r) return null;
    if (r.token !== navToken || performance.now() > r.until) { pendingRestore = null; return null; }
    return r;
  }

  document.addEventListener('keydown', () => { pendingRestore = null; }, true);
  window.addEventListener('wheel', () => { pendingRestore = null; }, { capture: true, passive: true });

  document.addEventListener('pointerdown', () => {
    pendingRestore = null;
    const s = getSelection();
    selAtDown = !!(s && !s.isCollapsed);
  }, true);

  /** Shows the dictionary for the word under (x, y). Returns false when there is no word there. */
  function lookupAt(x, y) {
    const r = wordRangeAt(x, y);
    if (!r) return false;
    const ch = chOfNode(r.startContainer);
    const root = ch >= 0 ? annRoot(ch) : null;
    if (!root || !root.contains(r.startContainer)) return false;
    const s = offsetOf(root, r.startContainer, r.startOffset);
    const word = r.toString();
    const mark = r.startContainer.parentElement.closest('mark.hl');
    clearTap();
    tapEls = drawRects(r, 'tap', true);
    const q = r.getBoundingClientRect();
    scrollNotified = false;
    A.onWordTap(word, ch, s, s + word.length, q.left, q.top, q.right, q.bottom, mark ? +mark.dataset.id : 0);
    return true;
  }

  /** Plain tap: open a highlight, otherwise let Kotlin turn the page / toggle the menus. */
  function plainTap(x, y, target) {
    const mark = target && target.closest && target.closest('mark.hl');
    if (mark) {
      const q = mark.getBoundingClientRect();
      A.onAnnotationTap(+mark.dataset.id, q.left, q.top, q.right, q.bottom);
      return;
    }
    A.onTap(x / innerWidth, y / innerHeight);
  }

  // Double-tap mode waits this long after a tap to see whether a second tap follows.
  const DOUBLE_TAP_MS = 280;
  let pendingTap = 0;
  let lastTap = null;

  document.addEventListener('click', e => {
    if (!ready) return;
    if (performance.now() < suppressClickUntil) { e.preventDefault(); return; }
    if (selAtDown) { selAtDown = false; return; }
    // Book content never navigates the reader page itself (<a>, image-map <area>, javascript: URLs, ...).
    const a = e.target.closest && e.target.closest('a, area, [href]');
    if (a) e.preventDefault();
    if (a && (a.dataset.ext || a.dataset.lch != null)) { followLink(a); return; }
    const x = e.clientX, y = e.clientY, target = e.target;
    if (!settings.doubleTap) {
      if (!lookupAt(x, y)) plainTap(x, y, target);
      return;
    }
    const now = performance.now();
    if (lastTap && now - lastTap.t < DOUBLE_TAP_MS && Math.hypot(x - lastTap.x, y - lastTap.y) < 40) {
      // Second tap of a double tap: show the meaning instead of the single-tap action.
      clearTimeout(pendingTap);
      lastTap = null;
      const s = getSelection();
      if (s && !s.isCollapsed) s.removeAllRanges();
      if (!lookupAt(x, y)) plainTap(x, y, target);
      return;
    }
    lastTap = { t: now, x: x, y: y };
    clearTimeout(pendingTap);
    pendingTap = setTimeout(() => { lastTap = null; plainTap(x, y, target); }, DOUBLE_TAP_MS);
  });

  // ------------------------------------------------------------------ selection
  function selInfo(r) {
    const ch = chOfNode(r.startContainer);
    if (ch < 0) return null;
    const root = annRoot(ch);
    if (!root || !root.contains(r.startContainer)) return null;
    let s = offsetOf(root, r.startContainer, r.startOffset);
    let e = root.contains(r.endContainer) ? offsetOf(root, r.endContainer, r.endOffset) : root.textContent.length;
    const full = root.textContent;
    while (s < e && /\s/.test(full[s])) s++;
    while (e > s && /\s/.test(full[e - 1])) e--;
    if (e <= s) return null;
    const q = r.getBoundingClientRect();
    return { ch: ch, s: s, e: e, text: full.slice(s, e), l: q.left, t: q.top, r: q.right, b: q.bottom };
  }

  // The live selection can collapse a moment before Kotlin asks for it (focus moves to the action bar),
  // so the last good one is kept briefly, but never one the user has since replaced or abandoned.
  const SEL_GRACE_MS = 1500;
  let selLostAt = 0;

  document.addEventListener('selectionchange', () => {
    const s = getSelection();
    if (!s || s.isCollapsed || !s.rangeCount) {
      if (lastSel && !selLostAt) selLostAt = performance.now();
      return;
    }
    lastSel = selInfo(s.getRangeAt(0)); // null for an unusable selection, so an older passage can't be acted on
    selLostAt = 0;
    if (popupOpen) A.onUserScroll();
  });

  R.captureSelection = () => {
    const s = getSelection();
    if (s && !s.isCollapsed && s.rangeCount) return selInfo(s.getRangeAt(0));
    if (!lastSel || !sectionOf(lastSel.ch)) return null;
    if (selLostAt && performance.now() - selLostAt > SEL_GRACE_MS) { lastSel = null; return null; }
    return lastSel;
  };
  R.clearSelection = () => {
    const s = getSelection();
    if (s) s.removeAllRanges();
    lastSel = null;
    selLostAt = 0;
  };

  // ------------------------------------------------------------------ chapters
  const htmlCache = new Map();

  function fetchChapter(i) {
    return fetch('/book/chapter/' + i).then(r => {
      if (!r.ok) throw new Error('chapter ' + i + ': ' + r.status);
      return r.text();
    });
  }

  function getChapter(i) {
    if (htmlCache.has(i)) return htmlCache.get(i);
    const p = fetchChapter(i);
    htmlCache.set(i, p);
    p.catch(() => htmlCache.delete(i));
    if (htmlCache.size > 8) {
      for (const k of htmlCache.keys()) {
        if (htmlCache.size <= 8) break;
        if (Math.abs(k - i) > 2) htmlCache.delete(k);
      }
    }
    return p;
  }

  function buildSection(i, html, sentinel) {
    const s = document.createElement('section');
    s.className = 'chapter';
    s.dataset.ch = i;
    // Lazy images change the layout after the saved position has been restored; load them up front.
    s.innerHTML = html.replace(/\sloading\s*=\s*(["']?)lazy\1/gi, '');
    if (sentinel) {
      const end = document.createElement('span');
      end.className = 'pg-end';
      s.appendChild(end);
    }
    return s;
  }

  function waitImages(sec, ms) {
    const imgs = Array.from(sec.querySelectorAll('img')).filter(i => !i.complete);
    if (!imgs.length) return Promise.resolve();
    return Promise.race([
      Promise.all(imgs.map(i => new Promise(r => { i.addEventListener('load', r, { once: true }); i.addEventListener('error', r, { once: true }); }))),
      sleep(ms)
    ]);
  }

  // ------------------------------------------------------------------ page numbers (book-wide)
  let counts = [];          // measured pages per chapter (0 = not measured yet)
  let starts = [0];
  let totalPages = 1;
  let measureGen = 0;

  function measureKey() {
    return ['pg2', bookKey, innerWidth, innerHeight, topInset, bottomInset, settings.fontSize, settings.lineHeight,
      settings.margin, settings.fontFamily].join('|');
  }

  function computeStarts() {
    let kw = 0, kp = 0;
    for (let i = 0; i < count; i++) if (counts[i]) { kw += weights[i] || 1; kp += counts[i]; }
    const perWeight = kp ? kp / kw : 1 / 1800;
    starts = new Array(count);
    let acc = 0;
    for (let i = 0; i < count; i++) {
      starts[i] = acc;
      acc += counts[i] || Math.max(1, Math.round((weights[i] || 1) * perWeight));
    }
    totalPages = Math.max(1, acc);
  }

  function chapterPagesEstimate(ch) {
    return (starts[ch + 1] != null ? starts[ch + 1] : totalPages) - starts[ch];
  }

  let pagesSendTimer = 0;
  function sendPages() {
    clearTimeout(pagesSendTimer);
    pagesSendTimer = setTimeout(() => {
      if (mode === 'pdf') return;
      computeStarts();
      A.onPages(JSON.stringify({ starts: starts, total: totalPages, exact: counts.every(c => c > 0) }));
      report();
    }, 120);
  }

  function setupMeasureBox() {
    measureBox.style.width = innerWidth + 'px';
    measureBox.style.height = innerHeight + 'px';
    styleColumns(measureBox);
  }

  function pagesOf(box) {
    const end = box.querySelector('.pg-end');
    if (!end) return 1;
    const x = end.getBoundingClientRect().left - box.getBoundingClientRect().left + box.scrollLeft;
    return Math.max(1, Math.floor(Math.max(0, x - 1) / innerWidth) + 1);
  }

  async function measureAll() {
    if (mode !== 'flow') return;
    const gen = ++measureGen;
    const key = measureKey();
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(key) || 'null'); } catch (e) { saved = null; }
    counts = Array.isArray(saved) && saved.length === count ? saved : new Array(count).fill(0);
    sendPages();
    setupMeasureBox();
    for (let i = 0; i < count; i++) {
      if (gen !== measureGen) return;
      if (counts[i]) continue;
      let html;
      try { html = await fetchChapter(i); } catch (e) { counts[i] = 1; continue; }
      if (gen !== measureGen) return;
      measureBox.innerHTML = '';
      measureBox.appendChild(buildSection(i, html, true));
      counts[i] = pagesOf(measureBox);
      measureBox.innerHTML = '';
      if (i % 4 === 3) sendPages();
      await sleep(8);
    }
    if (gen !== measureGen) return;
    try { localStorage.setItem(key, JSON.stringify(counts)); } catch (e) { /* storage full */ }
    sendPages();
  }

  let remeasureTimer = 0;
  function remeasureSoon() {
    clearTimeout(remeasureTimer);
    measureGen++;
    remeasureTimer = setTimeout(measureAll, 500);
  }

  // ------------------------------------------------------------------ position
  function visibleSection() {
    const y = topInset + 2;
    for (const s of content.querySelectorAll(SECTION_SEL)) {
      const r = s.getBoundingClientRect();
      if (r.bottom > y) return { s: s, r: r };
    }
    return null;
  }

  function currentPos() {
    if (paged()) {
      const sec = content.querySelector('section.chapter');
      if (!sec) return null;
      const n = livePages();
      return { ch: +sec.dataset.ch, p: n > 1 ? curPage() / n : 0 };
    }
    const v = visibleSection();
    if (!v || v.s.dataset.ch == null) return null;
    return { ch: +v.s.dataset.ch, p: clamp((topInset - v.r.top) / Math.max(1, v.r.height), 0, 1) };
  }

  let reportTimer = 0;
  function report() {
    clearTimeout(reportTimer);
    reportTimer = setTimeout(() => {
      const p = currentPos();
      if (!p) return;
      A.onPosition(p.ch, p.p);
      if (mode === 'pdf') { A.onPage(p.ch + 1, count, 1, 1); return; }
      let chPage, chPages;
      if (paged()) { chPage = curPage(); chPages = livePages(); }
      else { chPages = chapterPagesEstimate(p.ch); chPage = Math.min(chPages - 1, Math.floor(p.p * chPages)); }
      A.onPage(starts[p.ch] + chPage + 1, totalPages, chPage + 1, chPages);
    }, 120);
  }

  function scrollToProgress(sec, p) {
    window.scrollTo(0, Math.max(0, sec.offsetTop + p * sec.offsetHeight - topInset));
  }

  // ------------------------------------------------------------------ scroll layout
  function endMarker() {
    if (content.querySelector('.end-of-book')) return;
    const d = document.createElement('div');
    d.className = 'end-of-book';
    d.textContent = '— THE END —';
    content.appendChild(d);
  }

  function lastSection() {
    const secs = content.querySelectorAll('section.chapter');
    return secs.length ? secs[secs.length - 1] : null;
  }

  async function appendNext() {
    if (loadingNext || paged() || mode !== 'flow') return;
    const last = lastSection();
    if (!last) return;
    const i = +last.dataset.ch + 1;
    if (i >= count) { endMarker(); return; }
    loadingNext = true;
    const token = navToken;
    try {
      const html = await getChapter(i);
      if (token !== navToken || lastSection() !== last) return;
      content.appendChild(buildSection(i, html, false));
      applyAnnotations(i);
      pruneTop();
    } catch (e) { A.log(String(e)); }
    finally { loadingNext = false; }
  }

  async function prependPrev() {
    if (loadingPrev || paged() || mode !== 'flow') return;
    const first = content.querySelector('section.chapter');
    if (!first) return;
    const i = +first.dataset.ch - 1;
    if (i < 0) return;
    loadingPrev = true;
    const token = navToken;
    try {
      const html = await getChapter(i);
      if (token !== navToken || content.querySelector('section.chapter') !== first) return;
      const s = buildSection(i, html, false);
      const before = first.getBoundingClientRect().top;
      content.insertBefore(s, first);
      applyAnnotations(i);
      const after = first.getBoundingClientRect().top;
      if (after !== before) window.scrollBy(0, after - before);
      pruneBottom();
    } catch (e) { A.log(String(e)); }
    finally { loadingPrev = false; }
  }

  function pruneTop() {
    while (content.querySelectorAll('section.chapter').length > MAX_SECTIONS) {
      const f = content.querySelector('section.chapter');
      if (f.getBoundingClientRect().bottom > -innerHeight) break;
      const next = f.nextElementSibling;
      const before = next.getBoundingClientRect().top;
      f.remove();
      const after = next.getBoundingClientRect().top;
      if (after !== before) window.scrollBy(0, after - before);
    }
  }

  function pruneBottom() {
    while (content.querySelectorAll('section.chapter').length > MAX_SECTIONS) {
      const l = lastSection();
      if (l.getBoundingClientRect().top < innerHeight * 2) break;
      l.remove();
      const end = content.querySelector('.end-of-book');
      if (end) end.remove();
    }
  }

  function checkLoad() {
    if (!ready || mode !== 'flow' || paged()) return;
    const sh = document.documentElement.scrollHeight;
    if (scrollY + innerHeight > sh - innerHeight * 2.5) appendNext();
    if (scrollY < innerHeight * 1.5) prependPrev();
  }

  async function openScroll(ch, progress) {
    const token = ++navToken;
    const html = await getChapter(ch);
    if (token !== navToken) return null;
    content.innerHTML = '';
    mountOverlays();
    const s = buildSection(ch, html, false);
    content.appendChild(s);
    applyAnnotations(ch);
    if (progress > 0) await waitImages(s, 600);
    await nextFrame();
    scrollToProgress(s, progress || 0);
    return s;
  }

  // ------------------------------------------------------------------ paged layout
  let livePageCount = 1;
  let anim = 0;

  function styleColumns(box) {
    const m = settings.margin || 22;
    box.style.columnWidth = Math.max(50, innerWidth - 2 * m) + 'px';
    box.style.columnGap = (2 * m) + 'px';
  }

  function curPage() { return Math.round(content.scrollLeft / innerWidth); }
  function livePages() { return livePageCount; }

  function recountLive() {
    if (!paged()) return;
    const sec = content.querySelector('section.chapter');
    if (!sec) return;
    const n = pagesOf(content);
    const changed = livePageCount !== n;
    livePageCount = n;
    const ch = +sec.dataset.ch;
    if (counts[ch] !== n) { counts[ch] = n; sendPages(); }
    const r = changed && restoreStillWanted();
    if (r) setPage(Math.min(n - 1, Math.round(r.progress * n)), false);
    else if (curPage() > n - 1) setPage(n - 1, false);
  }

  function setPage(p, animate) {
    cancelAnimationFrame(anim);
    const target = clamp(p, 0, livePageCount - 1) * innerWidth;
    if (!animate) { content.scrollLeft = target; report(); return Promise.resolve(); }
    const from = content.scrollLeft;
    const dist = target - from;
    if (Math.abs(dist) < 1) { content.scrollLeft = target; report(); return Promise.resolve(); }
    const dur = clamp(Math.abs(dist) / innerWidth * 260, 140, 320);
    const t0 = performance.now();
    return new Promise(res => {
      const step = now => {
        const t = clamp((now - t0) / dur, 0, 1);
        const e = 1 - Math.pow(1 - t, 3);
        content.scrollLeft = from + dist * e;
        if (t < 1) anim = requestAnimationFrame(step);
        else { report(); res(); }
      };
      anim = requestAnimationFrame(step);
    });
  }

  function pageOfRect(q) {
    const x = q.left - content.getBoundingClientRect().left + content.scrollLeft;
    return Math.floor(Math.max(0, x) / innerWidth);
  }

  async function openPaged(ch, where) {
    const token = ++navToken;
    const html = await getChapter(ch);
    if (token !== navToken) return null;
    cancelAnimationFrame(anim);
    content.style.transform = '';
    content.innerHTML = '';
    const s = buildSection(ch, html, true);
    content.appendChild(s);
    mountOverlays();
    applyAnnotations(ch);
    s.querySelectorAll('img').forEach(img => {
      if (!img.complete) img.addEventListener('load', () => recountLive(), { once: true });
      img.loading = 'eager';
    });
    await nextFrame();
    livePageCount = pagesOf(content);
    if (counts[ch] !== livePageCount) { counts[ch] = livePageCount; sendPages(); }
    let page = 0;
    if (where) {
      if (where.end) page = livePageCount - 1;
      else if (where.page != null) page = where.page;
      else if (where.progress != null) page = Math.round(where.progress * livePageCount);
    }
    setPage(page, false);
    // Warm the neighbours so page turns across chapters are instant.
    if (ch + 1 < count) getChapter(ch + 1);
    if (ch > 0) getChapter(ch - 1);
    return s;
  }

  let chapterTurning = false;
  async function turnPage(dir) {
    if (!paged() || chapterTurning) return;
    pendingRestore = null;
    const p = curPage() + dir;
    if (p >= 0 && p < livePageCount) { await setPage(p, true); return; }
    const sec = content.querySelector('section.chapter');
    if (!sec) return;
    const ch = +sec.dataset.ch + dir;
    if (ch < 0 || ch >= count) { bounce(dir); return; }
    chapterTurning = true;
    try {
      content.classList.add('turning');
      await sleep(90);
      await openPaged(ch, dir > 0 ? { page: 0 } : { end: true });
      content.classList.remove('turning');
    } finally { chapterTurning = false; }
  }

  function bounce(dir) {
    content.style.transition = 'transform .12s ease-out';
    content.style.transform = 'translateX(' + (-dir * 28) + 'px)';
    setTimeout(() => {
      content.style.transform = '';
      setTimeout(() => { content.style.transition = ''; }, 160);
    }, 120);
  }

  // Swipe handling: the column strip follows the finger and snaps to a page on release.
  let drag = null;
  content.addEventListener('touchstart', e => {
    if (!paged() || e.touches.length !== 1 || chapterTurning) { drag = null; return; }
    const s = getSelection();
    if (s && !s.isCollapsed) { drag = null; return; }
    cancelAnimationFrame(anim);
    const t = e.touches[0];
    drag = { x: t.clientX, y: t.clientY, sl: content.scrollLeft, t: performance.now(), active: false };
  }, { passive: true });

  content.addEventListener('touchmove', e => {
    if (!drag) return;
    const t = e.touches[0];
    const dx = t.clientX - drag.x, dy = t.clientY - drag.y;
    if (!drag.active) {
      if (Math.abs(dx) < 10 || Math.abs(dx) < Math.abs(dy) * 1.2) return;
      const s = getSelection();
      if (s && !s.isCollapsed) { drag = null; return; }
      drag.active = true;
      if (popupOpen) A.onUserScroll();
    }
    e.preventDefault();
    const max = (livePageCount - 1) * innerWidth;
    let x = drag.sl - dx, over = 0;
    if (x < 0) { over = -x; x = 0; } else if (x > max) { over = max - x; x = max; }
    content.scrollLeft = x;
    content.style.transform = over ? 'translateX(' + (over * 0.35) + 'px)' : '';
  }, { passive: false });

  function endDrag(e) {
    if (!drag) return;
    const d = drag;
    drag = null;
    if (!d.active) return;
    suppressClickUntil = performance.now() + 350;
    const t = e.changedTouches[0];
    const dx = t.clientX - d.x;
    const v = dx / Math.max(1, performance.now() - d.t);
    const startPage = Math.round(d.sl / innerWidth);
    let dir = 0;
    if (dx < -innerWidth * 0.16 || v < -0.35) dir = 1;
    else if (dx > innerWidth * 0.16 || v > 0.35) dir = -1;
    if (content.style.transform) {
      content.style.transition = 'transform .18s ease-out';
      content.style.transform = '';
      setTimeout(() => { content.style.transition = ''; }, 200);
    }
    const target = startPage + dir;
    if (dir !== 0 && (target < 0 || target >= livePageCount)) {
      setPage(startPage, false);
      turnPage(dir);
    } else {
      setPage(target, true);
    }
  }
  content.addEventListener('touchend', endDrag);
  content.addEventListener('touchcancel', endDrag);

  function setLayout(l) {
    if (l !== layout) {
      navToken++;
      content.innerHTML = '';
      content.scrollLeft = 0;
      window.scrollTo(0, 0);
    }
    layout = l;
    document.documentElement.dataset.layout = l;
    content.classList.toggle('paged', l === 'paged');
    if (l === 'paged') styleColumns(content);
    else { content.style.columnWidth = ''; content.style.columnGap = ''; content.scrollLeft = 0; }
  }

  // ------------------------------------------------------------------ pdf mode
  let pdf = null, baseRatio = 1.414, zoom = 1, io = null, layoutWidth = 0;

  function loadScript(src) {
    return new Promise((res, rej) => {
      const s = document.createElement('script');
      s.src = src; s.onload = res; s.onerror = () => rej(new Error('load ' + src));
      document.head.appendChild(s);
    });
  }

  function pageWidth() { return Math.floor((innerWidth - 12) * zoom); }

  function layoutPdfPages() {
    const w = pageWidth();
    layoutWidth = innerWidth;
    content.querySelectorAll('.page').forEach(p => {
      const ar = +p.dataset.ar || baseRatio;
      p.style.width = w + 'px';
      p.style.height = Math.floor(w * ar) + 'px';
    });
    content.style.overflowX = zoom > 1 ? 'auto' : '';
  }

  function unrender(div) {
    div._gen = (div._gen || 0) + 1;
    const c = div.querySelector('canvas');
    if (c) { c.width = 0; c.height = 0; c.remove(); }
    div.dataset.state = '';
  }

  async function renderPage(div) {
    if (div.dataset.state === 'done' || div.dataset.state === 'loading') return div._promise;
    div.dataset.state = 'loading';
    const gen = div._gen = (div._gen || 0) + 1;
    div._promise = (async () => {
      const i = +div.dataset.ch;
      const page = await pdf.getPage(i + 1);
      const vp1 = page.getViewport({ scale: 1 });
      const ar = vp1.height / vp1.width;
      if (Math.abs(ar - (+div.dataset.ar || baseRatio)) > 0.002) {
        const before = div.getBoundingClientRect().top;
        div.dataset.ar = ar;
        const oldH = div.offsetHeight;
        div.style.height = Math.floor(pageWidth() * ar) + 'px';
        if (before < topInset) window.scrollBy(0, div.offsetHeight - oldH);
      }
      const scale = pageWidth() / vp1.width;
      const vp = page.getViewport({ scale: scale });
      const dpr = Math.min(window.devicePixelRatio || 1, 2.5);
      const canvas = document.createElement('canvas');
      canvas.width = Math.floor(vp.width * dpr);
      canvas.height = Math.floor(vp.height * dpr);
      await page.render({
        canvasContext: canvas.getContext('2d', { alpha: false }),
        viewport: vp,
        transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : null
      }).promise;
      if (gen !== div._gen) { canvas.width = 0; return; }
      const old = div.querySelector('canvas');
      if (old) old.remove();
      const num = div.querySelector('.pnum');
      if (num) num.remove();
      div.insertBefore(canvas, div.firstChild);
      let tl = div.querySelector('.textLayer');
      if (tl && +tl.dataset.scale !== scale) { tl.remove(); tl = null; }
      if (!tl) {
        tl = document.createElement('div');
        tl.className = 'textLayer';
        tl.dataset.scale = scale;
        tl.style.setProperty('--scale-factor', scale);
        div.appendChild(tl);
        await pdfjsLib.renderTextLayer({
          textContentSource: page.streamTextContent(),
          container: tl, viewport: vp, textDivs: []
        }).promise;
        tl.dataset.ready = '1';
        applyAnnotations(i);
      }
      if (gen === div._gen) div.dataset.state = 'done';
    })().catch(e => { div.dataset.state = ''; A.log('pdf page ' + div.dataset.ch + ': ' + e); });
    return div._promise;
  }

  function onIntersect(entries) {
    for (const en of entries) {
      if (en.isIntersecting) renderPage(en.target);
      else if (en.target.dataset.state === 'done') unrender(en.target);
    }
  }

  async function resolveOutline(items, depth, out) {
    for (const it of items || []) {
      let page = -1;
      try {
        let dest = it.dest;
        if (typeof dest === 'string') dest = await pdf.getDestination(dest);
        if (Array.isArray(dest) && dest[0]) {
          page = typeof dest[0] === 'object' ? await pdf.getPageIndex(dest[0]) : +dest[0];
        }
      } catch (e) { page = -1; }
      out.push({ title: it.title || '', page: page, depth: depth });
      if (it.items && it.items.length && out.length < 2000) await resolveOutline(it.items, depth + 1, out);
    }
    return out;
  }

  async function openPdf() {
    content.classList.add('pdf');
    await loadScript('/assets/pdfjs/pdf.min.js');
    pdfjsLib.GlobalWorkerOptions.workerSrc = '/assets/pdfjs/pdf.worker.min.js';
    pdf = await pdfjsLib.getDocument({
      url: '/book/file',
      cMapUrl: '/assets/pdfjs/cmaps/', cMapPacked: true,
      standardFontDataUrl: '/assets/pdfjs/standard_fonts/',
      disableRange: false, disableStream: false, isEvalSupported: false
    }).promise;
    count = pdf.numPages;
    const p1 = await pdf.getPage(1);
    const v = p1.getViewport({ scale: 1 });
    baseRatio = v.height / v.width;
    const frag = document.createDocumentFragment();
    for (let i = 0; i < count; i++) {
      const d = document.createElement('div');
      d.className = 'page';
      d.dataset.ch = i;
      const n = document.createElement('div');
      n.className = 'pnum';
      n.textContent = String(i + 1);
      d.appendChild(n);
      frag.appendChild(d);
    }
    content.appendChild(frag);
    layoutPdfPages();
    io = new IntersectionObserver(onIntersect, { rootMargin: '120% 0px' });
    content.querySelectorAll('.page').forEach(p => io.observe(p));
    let title = '';
    try { const md = await pdf.getMetadata(); title = (md && md.info && md.info.Title) || ''; } catch (e) { /* ignore */ }
    let toc = [];
    try { toc = await resolveOutline(await pdf.getOutline(), 0, []); } catch (e) { toc = []; }
    A.onPdfInfo(JSON.stringify({ pages: count, title: title, toc: toc }));
  }

  function relayoutPdf() {
    const pos = currentPos();
    layoutPdfPages();
    content.querySelectorAll('.page').forEach(p => {
      unrender(p);
      const tl = p.querySelector('.textLayer');
      if (tl) tl.remove();
    });
    if (pos) scrollToProgress(sectionOf(pos.ch), pos.p);
    if (io) { io.disconnect(); content.querySelectorAll('.page').forEach(p => io.observe(p)); }
  }

  async function pdfPageText(i) {
    const page = await pdf.getPage(i + 1);
    const tc = await page.getTextContent();
    return tc.items.map(it => it.str || '').join('');
  }

  // ------------------------------------------------------------------ public api
  R.open = async (o) => {
    syncViewport();
    mode = o.mode;
    count = o.count || 0;
    bookKey = o.bookKey || '';
    weights = o.weights || [];
    R.setInsets(o.top, o.bottom);
    R.applySettings(o.settings, true);
    try {
      if (mode === 'pdf') {
        await openPdf();
        await nextFrame();
        const sec = sectionOf(clamp(o.ch, 0, count - 1));
        if (sec) scrollToProgress(sec, o.progress || 0);
      } else {
        setLayout(o.settings && o.settings.paged ? 'paged' : 'scroll');
        computeStarts();
        const ch0 = clamp(o.ch, 0, count - 1), p0 = o.progress || 0;
        const sec0 = paged() ? await openPaged(ch0, { progress: p0 }) : await openScroll(ch0, p0);
        if (sec0 && p0 > 0) {
          pendingRestore = { token: navToken, progress: p0, until: performance.now() + 4000 };
          waitImages(sec0, 2500).then(async () => {
            await nextFrame();
            if (!restoreStillWanted()) return;
            if (paged()) recountLive();
            else { scrollToProgress(sec0, p0); report(); }
            pendingRestore = null;
          });
        }
        setTimeout(measureAll, 700);
      }
      ready = true;
      A.onReady();
      setTimeout(() => { checkLoad(); report(); }, 60);
    } catch (e) {
      A.onError(String(e && e.message || e));
    }
  };

  R.goTo = async (ch, o) => {
    o = o || {};
    pendingRestore = null;
    ch = clamp(ch, 0, count - 1);
    clearTap();
    const hasTarget = o.s != null || o.find || o.anchor;
    let sec = sectionOf(ch);

    if (paged()) {
      if (!sec) {
        sec = await openPaged(ch, hasTarget ? { page: 0 } : (o.page != null ? { page: o.page } : { progress: o.progress || 0 }));
        if (!sec) return;
      } else if (!hasTarget) {
        setPage(o.page != null ? o.page : Math.round((o.progress || 0) * livePageCount), false);
      }
    } else if (mode === 'flow' && !sec) {
      sec = await openScroll(ch, o.progress || 0);
      if (!sec) return;
    } else if (!hasTarget) {
      scrollToProgress(sec, o.progress || 0);
    }

    if (mode === 'pdf' && (o.s != null || o.find)) {
      scrollToProgress(sec, 0);
      await renderPage(sec);
    }
    const root = annRoot(ch);
    let r = null;
    if (root && o.s != null) r = rangeAt(root, o.s, o.e != null ? o.e : o.s + 1);
    else if (root && o.find) r = findNth(root, o.find, o.nth || 0, !!o.whole);
    let el = null;
    if (!r && o.anchor && mode === 'flow') {
      el = sec.querySelector('[id="' + CSS.escape(o.anchor) + '"]') || sec.querySelector('[name="' + CSS.escape(o.anchor) + '"]');
    }
    if (paged()) {
      if (r || el) {
        await waitImages(sec, 400);
        recountLive();
        const q = r ? r.getBoundingClientRect() : el.getBoundingClientRect();
        setPage(pageOfRect(q), false);
        await nextFrame();
        if (r && o.flash !== false) drawRects(r, 'flash', false);
      }
    } else if (r) {
      if (mode === 'flow') await waitImages(sec, 400);
      const rect = r.getBoundingClientRect();
      window.scrollTo(0, Math.max(0, scrollY + rect.top - topInset - (innerHeight - topInset - bottomInset) * 0.3));
      await nextFrame();
      if (o.flash !== false) drawRects(r, 'flash', false);
    } else if (el) {
      window.scrollTo(0, Math.max(0, el.getBoundingClientRect().top + scrollY - topInset - 8));
    }
    setTimeout(() => { checkLoad(); report(); }, 80);
  };

  /** 1-based book page number. */
  R.goToPage = n => {
    if (mode === 'pdf') { R.goTo(n - 1, { progress: 0 }); return; }
    computeStarts();
    const p = clamp(n - 1, 0, totalPages - 1);
    let ch = 0;
    while (ch < count - 1 && starts[ch + 1] <= p) ch++;
    const within = p - starts[ch];
    if (paged()) R.goTo(ch, { page: within });
    else R.goTo(ch, { progress: within / Math.max(1, chapterPagesEstimate(ch)) });
  };

  R.page = dir => {
    if (paged()) { turnPage(dir); return; }
    const h = innerHeight - topInset - bottomInset - 40;
    window.scrollBy({ top: dir * h, behavior: 'smooth' });
  };

  R.refreshAnnotations = ch => {
    if (ch == null || ch < 0) content.querySelectorAll(SECTION_SEL).forEach(s => applyAnnotations(+s.dataset.ch));
    else applyAnnotations(ch);
  };

  R.setPopupOpen = v => { popupOpen = !!v; scrollNotified = false; if (!v) clearTap(); };

  R.setInsets = (t, b) => {
    const changed = (t || 0) !== topInset || (b || 0) !== bottomInset;
    topInset = t || 0; bottomInset = b || 0;
    const st = document.documentElement.style;
    st.setProperty('--top', topInset + 'px');
    st.setProperty('--bottom', bottomInset + 'px');
    if (changed && ready && paged()) { relayoutPaged(); remeasureSoon(); }
  };

  async function relayoutPaged() {
    const pos = currentPos();
    styleColumns(content);
    await nextFrame();
    livePageCount = pagesOf(content);
    if (pos) setPage(Math.round(pos.p * livePageCount), false);
  }

  R.applySettings = (s, initial) => {
    if (!s) return;
    const prev = settings;
    settings = s;
    const pos = initial ? null : currentPos();
    const st = document.documentElement.style;
    st.setProperty('--fs', s.fontSize + 'px');
    st.setProperty('--lh', String(s.lineHeight));
    st.setProperty('--margin', s.margin + 'px');
    st.setProperty('--font', s.fontFamily);
    st.setProperty('--bg', s.bg);
    st.setProperty('--fg', s.fg);
    st.setProperty('--muted', s.muted);
    st.setProperty('--link', s.link);
    st.setProperty('--rule', s.rule);
    st.setProperty('--align', s.justify ? 'justify' : 'left');
    document.documentElement.dataset.dark = s.dark ? '1' : '0';
    if (s.dark) {
      st.setProperty('--hl-yellow', 'rgba(255, 213, 79, .32)');
      st.setProperty('--hl-green', 'rgba(129, 199, 132, .32)');
      st.setProperty('--hl-blue', 'rgba(100, 181, 246, .32)');
      st.setProperty('--hl-pink', 'rgba(240, 98, 146, .32)');
      st.setProperty('--hl-purple', 'rgba(179, 157, 219, .34)');
      st.setProperty('--tap', 'rgba(255, 193, 7, .28)');
    } else {
      ['--hl-yellow', '--hl-green', '--hl-blue', '--hl-pink', '--hl-purple', '--tap'].forEach(k => st.removeProperty(k));
    }
    if (initial) { zoom = s.pdfZoom || 1; return; }

    if (mode === 'pdf') {
      const newZoom = s.pdfZoom || 1;
      if (newZoom !== zoom) { zoom = newZoom; if (pdf) relayoutPdf(); }
      return;
    }
    const wantLayout = s.paged ? 'paged' : 'scroll';
    const geometryChanged = prev.fontSize !== s.fontSize || prev.lineHeight !== s.lineHeight ||
      prev.margin !== s.margin || prev.fontFamily !== s.fontFamily;
    if (wantLayout !== layout && pos) {
      setLayout(wantLayout);
      R.goTo(pos.ch, { progress: pos.p });
      return;
    }
    if (geometryChanged) {
      if (paged()) relayoutPaged();
      else if (pos) requestAnimationFrame(() => { const sec = sectionOf(pos.ch); if (sec) scrollToProgress(sec, pos.p); });
      remeasureSoon();
    }
  };

  let searchToken = 0;
  R.searchPdf = async (q, whole, token) => {
    searchToken = token;
    const nq = normQuery(q);
    const res = [];
    for (let i = 0; i < count && res.length < 1000; i++) {
      if (searchToken !== token) return;
      let raw = '';
      try { raw = await pdfPageText(i); } catch (e) { continue; }
      const { norm } = normText(raw);
      const flat = raw.replace(/\s+/g, ' '); // same indices as norm, original case
      findAll(norm, nq, whole).forEach((p, k) => {
        const a = Math.max(0, p - 50), b = Math.min(norm.length, p + nq.length + 70);
        res.push({ ch: i, nth: k, snip: flat.slice(a, b), at: p - a, len: nq.length });
      });
      if (i % 20 === 19) A.onSearchProgress(token, (i + 1) / count);
    }
    if (searchToken === token) A.onSearchResults(token, JSON.stringify(res));
  };

  R.cancelSearch = () => { searchToken = -1; };

  // ------------------------------------------------------------------ events
  let scrollRaf = 0;
  window.addEventListener('scroll', () => {
    if (paged()) return;
    if (popupOpen && !scrollNotified) { scrollNotified = true; A.onUserScroll(); }
    if (scrollRaf) return;
    scrollRaf = requestAnimationFrame(() => {
      scrollRaf = 0;
      checkLoad();
      report();
    });
  }, { passive: true });

  let resizeTimer = 0;
  let lastW = innerWidth, lastH = innerHeight;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    syncViewport();
    resizeTimer = setTimeout(() => {
      const changed = innerWidth !== lastW || innerHeight !== lastH;
      lastW = innerWidth; lastH = innerHeight;
      if (mode === 'pdf' && pdf && Math.abs(innerWidth - layoutWidth) > 2) relayoutPdf();
      if (mode === 'flow' && changed && ready) {
        if (paged()) relayoutPaged();
        remeasureSoon();
      }
      checkLoad();
    }, 200);
  });

  A.onLoaded();
})();
