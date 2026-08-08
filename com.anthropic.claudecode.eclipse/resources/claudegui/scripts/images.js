/* images.js — pasted-image attachments for the composer, VSCode-style
   (joebiden4): a screenshot/image paste (Ctrl+V) into the input becomes a CHIP
   — tiny thumbnail + "image.png" + "W×H" dimensions — not a big thumbnail. The
   chip shows a floating preview of the full image on hover and a remove × on
   hover; the same chip is echoed in the sent user bubble. Images are held per-tab
   as base64 and sent inline as Anthropic image content blocks (built in Rust from
   the {media_type,data} array). WebView2 delivers image blobs on the paste event's
   clipboardData, so we read the clipboard directly in JS — no CLI round-trip. */

/** Per-tab pending images: [{ media_type, data(base64), url(dataURL), w, h, name }]. */
function pendingImages(tab) {
  const t = tab || activeTab();
  if (!t) return [];
  if (!t.images) t.images = [];
  return t.images;
}
/** @returns {boolean} whether the active tab has any pending image (send-enable). */
function hasPendingImages() { const t = activeTab(); return !!(t && t.images && t.images.length); }

/** JSON the Rust side turns into image content blocks (data only — no preview fields). */
function pendingImagesJson(tab) {
  return JSON.stringify(pendingImages(tab).map(im => ({ media_type: im.media_type, data: im.data })));
}

/** Clipboard bitmaps have no filename; VSCode labels them "image.<ext>". */
function imageName(mediaType) {
  const ext = ((mediaType || 'image/png').split('/')[1] || 'png').split('+')[0];
  return 'image.' + ext;
}

/**
 * Adds one image (from a data URL); measures W×H async, then re-renders.
 * @param {string} dataUrl
 * @param {Tab} [tab]  the conversation it belongs to — defaults to the active one. A
 *   download that finishes after the user switched tabs still lands where it was pasted,
 *   and only redraws the strip when that tab is the one on screen (switching back
 *   re-renders anyway).
 */
function addPendingImage(dataUrl, tab) {
  const m = /^data:([^;,]+)(?:;base64)?,(.*)$/.exec(dataUrl || '');
  if (!m) return;
  const media_type = m[1] || 'image/png';
  const data = m[2] || '';
  if (!data) return;
  const im = { media_type, data, url: dataUrl, w: 0, h: 0, name: imageName(media_type) };
  pendingImages(tab).push(im);
  const onScreen = () => !tab || tab === activeTab();
  // Read natural dimensions off-screen, then refresh the chip's "W×H".
  const probe = new Image();
  probe.onload = () => {
    im.w = probe.naturalWidth; im.h = probe.naturalHeight;
    if (onScreen()) renderPendingImages();
  };
  probe.src = dataUrl;
  if (!onScreen()) return;
  renderPendingImages();
  syncComposer();
}

/**
 * Rebuilds a chip-ready image from a stored transcript block. Session history
 * gives back only {media_type, data} — the data URL, name and (lazily, in
 * makeImageChip) the dimensions are derived here.
 * @param {{media_type?: string, data?: string}} b
 */
function imageFromBlock(b) {
  const mt = (b && b.media_type) || 'image/png';
  const data = (b && b.data) || '';
  if (!data) return null;
  return { media_type: mt, data, url: 'data:' + mt + ';base64,' + data, w: 0, h: 0, name: imageName(mt) };
}

/** Reads a pasted image File/Blob into a data URL, then adds it. */
function readPastedImage(file) {
  if (!file) return;
  const r = new FileReader();
  r.onload = () => addPendingImage(String(r.result || ''));
  r.readAsDataURL(file);
}

function clearPendingImages(tab) {
  const t = tab || activeTab();
  if (t) t.images = [];
  renderPendingImages();
}

/**
 * Builds a VSCode-style image chip: tiny thumbnail + name + dimensions. CLICKING
 * the chip body opens the full image in a centered lightbox (joebiden5); hovering
 * only shows the native filename tooltip. Removable chips also get an × on hover.
 * @param {{url: string, name: string, w: number, h: number}} im
 * @param {(() => void)|null} onRemove  composer chips pass a remover; bubble chips don't
 */
function makeImageChip(im, onRemove) {
  const chip = document.createElement('span'); chip.className = 'img-chip';
  // Everything except the × is one click target that opens the preview.
  const open = document.createElement('span'); open.className = 'ic-open';
  open.title = im.name || 'image.png';
  const thumb = document.createElement('span'); thumb.className = 'ic-thumb';
  thumb.style.backgroundImage = 'url("' + im.url + '")';
  const name = document.createElement('span'); name.className = 'ic-name'; name.textContent = im.name || 'image.png';
  const dim = document.createElement('span'); dim.className = 'ic-dim';
  if (im.w && im.h) dim.textContent = im.w + '×' + im.h;
  else {
    // A chip rebuilt from history has no measured size yet — read it off-screen
    // and fill this chip's label in place (no re-render; the chip may live in a
    // reloaded bubble rather than the composer strip).
    const probe = new Image();
    probe.onload = () => {
      im.w = probe.naturalWidth; im.h = probe.naturalHeight;
      dim.textContent = im.w + '×' + im.h;
    };
    probe.src = im.url;
  }
  open.appendChild(thumb); open.appendChild(name); open.appendChild(dim);
  open.onclick = () => openLightbox(im);
  chip.appendChild(open);
  if (onRemove) {
    // Marks the chip as the kind that reveals an × on hover, so a sent bubble's chips
    // — which have no × — don't dim their dimensions for nothing.
    chip.classList.add('removable');
    const x = document.createElement('span'); x.className = 'ic-x'; x.innerHTML = ICONS.X; x.title = 'Remove';
    // Removing must not also open the preview.
    x.onclick = (e) => { e.stopPropagation(); onRemove(); };
    chip.appendChild(x);
  }
  return chip;
}

/* ---- lightbox ----
   Centered full image over a dimmed backdrop. Dismiss: × badge, backdrop click,
   or Esc. The Esc listener is capture-phase and registered only while open, so
   it takes precedence over the composer/menu Esc handlers, matching the other
   dialogs here (rewind, cards). */
function lightboxKey(e) {
  if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeLightbox(); }
}
function openLightbox(im) {
  const box = document.getElementById('lightbox');
  if (!box || !im) return;
  const img = box.querySelector('img');
  img.src = im.url;
  img.alt = im.name || 'image';
  box.classList.add('open');
  document.addEventListener('keydown', lightboxKey, true);
}
function closeLightbox() {
  const box = document.getElementById('lightbox');
  if (!box) return;
  box.classList.remove('open');
  const img = box.querySelector('img');
  if (img) img.src = '';        // release the data URL
  document.removeEventListener('keydown', lightboxKey, true);
}
/* Backdrop click closes; clicks on the image itself don't. */
(function wireLightbox() {
  const box = document.getElementById('lightbox');
  if (!box) return;
  box.onclick = (e) => { if (e.target === box) closeLightbox(); };
  const x = box.querySelector('.lb-x');
  if (x) x.onclick = (e) => { e.stopPropagation(); closeLightbox(); };
})();

/** Renders the active tab's pending-image chips into the composer strip. */
function renderPendingImages() {
  const strip = document.getElementById('pending-images');
  if (!strip) return;
  const imgs = pendingImages();
  // The strip is one horizontally scrolled row, and it's rebuilt on every change —
  // hold its scroll position so removing a chip doesn't fling the row back to the start.
  const scrollLeft = strip.scrollLeft;
  strip.innerHTML = '';
  strip.classList.toggle('show', imgs.length > 0);
  imgs.forEach((im, i) => {
    strip.appendChild(makeImageChip(im, () => {
      imgs.splice(i, 1); renderPendingImages(); syncComposer();
    }));
  });
  strip.scrollLeft = scrollLeft;
}

/* Capture image paste on the composer. A screenshot paste arrives as a file item
   with an image/* MIME type; when present we consume the event so the textarea
   doesn't also paste a filename/text alternative. Plain-text paste is untouched. */
input.addEventListener('paste', (e) => {
  /* A paste we just performed ourselves — see ccFlagHostPaste. Letting WebKit run its
     own paste on top of ours is what puts the text in twice on GTK, so this one is
     dropped. Only ours: a middle-click paste is unflagged and pastes normally. */
  if (window.__ccHostPaste) { window.__ccHostPaste = false; e.preventDefault(); return; }
  const data = e.clipboardData;
  if (!data) return;
  let took = false;
  for (const item of data.items || []) {
    if (item.kind === 'file' && item.type && item.type.indexOf('image/') === 0) {
      const file = item.getAsFile();
      if (file) { readPastedImage(file); took = true; }
    }
  }
  if (took) e.preventDefault();
});
