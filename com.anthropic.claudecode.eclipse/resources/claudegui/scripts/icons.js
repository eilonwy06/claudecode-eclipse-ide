/* icons.js — Inline SVG icon set + __NAME__ placeholder substitution. MUST load first:
   the substitution rewrites body.innerHTML, so every element reference must be
   taken after it runs. */

/* ---- inline SVG icon set ---- */
const ICONS = {
  SUNBURST: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><g>'+
    [0,45,90,135].map(a=>`<rect x="11" y="2" width="2" height="20" rx="1" transform="rotate(${a} 12 12)"/>`).join('')+'</g></svg>',
  /* Welcome-screen mascot — an original illustration (not derived from any Anthropic asset): a
     sleepy pixel-art crescent moon with a face, the eclipsed sun's glow peeking around its rim
     and a couple of stars nearby. Fixed palette (like the reference's own mascot choice) since
     this is decorative art, not a themeable UI icon — shape-rendering:crispEdges keeps the
     pixel blocks sharp instead of getting anti-aliased when scaled up from the 25x25 grid. */
  MASCOT: '<svg width="75" height="75" viewBox="0 0 25 25" shape-rendering="crispEdges"><path fill="#f5a623" d="M10 1h3v1h-3zM7 2h5v1H7zM5 3h3v1H5zM4 4h2v1H4zM3 5h2v1H3zM2 6h2v1H2zm0 1h1v1H2zM1 8h2v1H1zm0 1h1v1H1zm0 1h1v1H1zm-1 1h2v1H0zm0 1h2v1H0zm0 1h2v1H0zm0 1h2v1H0zm1 1h1v1H1zm0 1h1v1H1zm21 0h1v1h-1zM1 17h2v1H1zm20 0h2v1h-2zM2 18h1v1H2zm19 0h1v1h-1zM2 19h2v1H2zm18 0h2v1h-2zM3 20h2v1H3zm16 0h2v1h-2zM4 21h2v1H4zm14 0h2v1h-2zM5 22h3v1H5zm11 0h3v1h-3zm-9 1h10v1H7zm3 1h4v1h-4z"/><path fill="#4b4869" d="M8 3h3v1H8zM6 4h4v1H6zM5 5h5v1H5zM4 6h5v1H4zM3 7h6v1H3zm0 1h6v1H3zM2 9h7v1H2zm0 1h7v1H2zm0 1h7v1H2zm0 1h8v1H2zm0 1h8v1H2zm0 1h9v1H2zm0 1h10v1H2zm0 1h11v1H2zm1 1h13v1H3zm16 0h2v1h-2zM3 18h18v1H3zm1 1h16v1H4zm1 1h14v1H5zm1 1h12v1H6zm2 1h8v1H8z"/><path fill="#f6efe0" d="M4 15h1v1H4zm4 0h1v1H8zm-3 2h1v1H5zm1 1h2v1H6zm2-1h1v1H8z"/><path fill="#f7c948" d="M21 5h1v1h-1zm-1 1h3v1h-3zm1 1h1v1h-1zm3 5h1v1h-1zM15 1h1v1h-1z"/><path fill="#9b97c9" d="M17 6h1v1h-1zm6 3h1v1h-1zM13 0h1v1h-1z"/></svg>',
  X: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  SPLIT: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M12 4v16"/></svg>',
  LOCK: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>',
  DOTS: '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg>',
  CLOCK: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>',
  NEWCHAT: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="12" r="8.5"/><path d="M12 8.5v7M8.5 12h7"/></svg>',
  /* MCP servers (the / menu's Customize section): a plug. */
  PLUG: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M9 3v5M15 3v5"/><path d="M6 8h12v3a6 6 0 0 1-12 0z"/><path d="M12 17v4"/></svg>',
  REWIND: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v4h4"/></svg>',
  /* per-message actions badge (joebiden8): undo arrow — bar left-ward, tail curling
     back under it. Distinct from REWIND (the composer's circular-arrow button). */
  UNDO: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M8.5 9.5H15a4 4 0 0 1 0 8h-2"/><path d="M11.5 6.5l-3 3 3 3"/></svg>',
  CODEICON: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M9 8l-4 4 4 4M15 8l4 4-4 4"/></svg>',
  COPY: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/></svg>',
  MIC: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M6 11a6 6 0 0 0 12 0M12 17v4"/></svg>',
  /* The composer's + and / fill their whole 26px button, as the extension draws them: a
     20-unit glyph at 26px, so the cross is ~13px and the square ~14px. */
  PLUS: '<svg width="24" height="24" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round"><path d="M10 5v10M5 10h10"/></svg>',
  SLASH: '<svg width="24" height="24" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round"><rect x="4.5" y="4.5" width="11" height="11" rx="1.5"/><path d="M11.5 7l-3 6"/></svg>',
  FILEICON: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M7 3h7l4 4v14H7z"/><path d="M14 3v4h4"/></svg>',
  HAND: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M8 11V5.5a1.5 1.5 0 0 1 3 0V11M11 10.5V4.5a1.5 1.5 0 0 1 3 0V11M14 11V6a1.5 1.5 0 0 1 3 0v7a6 6 0 0 1-6 6h-1a6 6 0 0 1-5-2.7L4 14a1.6 1.6 0 0 1 2.6-1.8L8 14"/></svg>',
  SEND: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 19V5M6 11l6-6 6 6"/></svg>',
  STOP: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>',
  UPLOAD: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/></svg>',
  GLOBE: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.5 2.5 2.5 14.5 0 17M12 3.5c-2.5 2.5-2.5 14.5 0 17"/></svg>',
  SEARCH: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="10.5" cy="10.5" r="6.5"/><path d="M20 20l-5-5"/></svg>',
  /* search-scope: single user, for "titles + my own messages" */
  SEARCHOWN: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="8" r="3.2"/><path d="M5.5 19.5a6.5 6.5 0 0 1 13 0"/></svg>',
  /* search-scope: two chat bubbles, for "titles + full conversation" */
  SEARCHALL: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 5h10v6H8l-3 3v-3H4z"/><path d="M12 12.5h2l3 3v-3h2v-6h-3"/></svg>',
  CHECK: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5 9-10"/></svg>',
  PENCIL: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M14 6l4 4"/></svg>',
  MAP: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M9 4L3 6v14l6-2 6 2 6-2V4l-6 2-6-2z"/><path d="M9 4v14M15 6v14"/></svg>',
  BOLT: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M13 3L4 14h6l-1 7 9-11h-6z"/></svg>',
  GAUGE: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M4 17a8 8 0 1 1 16 0"/><path d="M12 17l4-4"/></svg>',
  MONITOR: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 20h8M12 17v3"/></svg>',
  TRASH: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13"/></svg>',
  /* A bookmark: outlined, and filled where something is bookmarked or its panel is open. */
  BOOKMARK: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M7 3h10a1 1 0 0 1 1 1v17l-6-4-6 4V4a1 1 0 0 1 1-1z"/></svg>',
  BOOKMARKED: '<svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M7 3h10a1 1 0 0 1 1 1v17l-6-4-6 4V4a1 1 0 0 1 1-1z"/></svg>',
  /* A lidded box with a slot: files a conversation under "Archived sessions". */
  ARCHIVE: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 5h18v4H3z"/><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9"/><path d="M10 13h4"/></svg>',
  /* An arrow turning back: takes a conversation out of the archive again. */
  UNARCHIVE: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 5l-5 5 5 5"/><path d="M4 10h11a5 5 0 0 1 0 10h-3"/></svg>',
  EYEOFF: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 3l18 18"/><path d="M10.6 10.7a2 2 0 0 0 2.8 2.8"/><path d="M9.9 4.2A9 9 0 0 1 21 12a9.6 9.6 0 0 1-2.3 3.2M6.1 6.1A9.7 9.7 0 0 0 3 12a9 9 0 0 0 11 7.2"/></svg>',
  BYPASS: '<svg width="15" height="15" viewBox="4.998499870300293 4.642997741699219 10.001999855041504 10.713608741760254" fill="none"><path d="M6.429 4.643c.665 0 1.223.455 1.381 1.071h4.869a2.321 2.321 0 1 1 0 4.643h-.745l-1.681 1.681a.357.357 0 0 1-.506 0l-1.68-1.68H7.32a1.607 1.607 0 0 0 0 3.213h4.869a1.428 1.428 0 1 1 0 .715H7.32a2.321 2.321 0 1 1 0-4.643h.745l1.681-1.681.056-.046a.36.36 0 0 1 .45.046l1.68 1.68h.746a1.607 1.607 0 0 0 0-3.213H7.81a1.428 1.428 0 1 1-1.38-1.786m7.142 8.571a.714.714 0 1 0 0 1.429.714.714 0 0 0 0-1.429M8.72 10 10 11.28 11.28 10 10 8.72zM6.43 5.357a.714.714 0 1 0 0 1.429.714.714 0 0 0 0-1.429" fill="currentColor"/></svg>',
  CHEVRON: '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 6l6 6-6 6"/></svg>',
  SPARK: '<svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l1.6 6.4L20 10l-6.4 1.6L12 18l-1.6-6.4L4 10l6.4-1.6z"/></svg>',
  BRAIN: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M9 3a3 3 0 0 0-3 3 3 3 0 0 0-1 5.8A3 3 0 0 0 7 17a2.5 2.5 0 0 0 5 .5V5a2 2 0 0 0-3-2zM15 3a3 3 0 0 1 3 3 3 3 0 0 1 1 5.8A3 3 0 0 1 17 17a2.5 2.5 0 0 1-5 .5"/></svg>',
  USER: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="8" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>',
  ZOOM: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3M8 11h6M11 8v6"/></svg>',
  DOWN: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M6 13l6 6 6-6"/></svg>',
  /* Root (supertab) row. Names carry no underscore on purpose: the placeholder
     substitution below matches __[A-Z]+__ only, so CHEV_DOWN would never expand. */
  FOLDER: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>',
  CHEVDOWN: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 5l6 6 6-6M6 12l6 6 6-6"/></svg>',
  CHEVUP: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 19l6-6 6 6M6 12l6-6 6 6"/></svg>',
  /* Single-chevron prev/next, for the find bar (CHEVUP/CHEVDOWN above are double-chevron,
     used by the supertabs row). */
  CHEVRONUP: '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 15l6-6 6 6"/></svg>',
  CHEVRONDOWN: '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6"/></svg>',
  /* The command menu's rows (cmdmenu.js). The extension's own menu is text only; these keep
     ours in the look it already had, one glyph per row, drawn for this plugin. */
  EXPORT: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6"/><path d="M20 4l-9 9"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>',
  LINES: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 7h16M4 12h11M4 17h7"/></svg>',
  HOOK: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="13" cy="5" r="2"/><path d="M13 7v8a4 4 0 0 1-8 0v-2"/><path d="M3 15l2-2 2 2"/></svg>',
  SHIELD: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z"/></svg>',
  INFO: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="8.5"/><path d="M12 11v6"/><path d="M12 7.5v.5"/></svg>',
  CUBE: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z"/><path d="M4 7.5l8 4.5 8-4.5M12 12v9"/></svg>',
  CMD: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="4" y="4" width="16" height="16" rx="3"/><path d="M14 8l-4 8"/></svg>',
  NOTE: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M6 3h12v18H6z"/><path d="M9 8h6M9 12h6M9 16h3"/></svg>',
  ADDON: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="4" y="4" width="7" height="7" rx="1"/><rect x="4" y="13" width="7" height="7" rx="1"/><rect x="13" y="13" width="7" height="7" rx="1"/><path d="M16.5 4v7M13 7.5h7"/></svg>',
  BAG: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M5 8h14l-1 12H6z"/><path d="M9 8V6a3 3 0 0 1 6 0v2"/></svg>',
  UPDATE: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20 12a8 8 0 0 1-14 5.3"/><path d="M4 12a8 8 0 0 1 14-5.3"/><path d="M18 3v4h-4M6 21v-4h4"/></svg>',
  TERMINAL: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3M12 15h5"/></svg>',
  GEAR: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="3"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"/></svg>',
  CHANGELOG: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H6v18h12V7z"/><path d="M14 3v4h4"/><path d="M12 10.5l1 2.5 2.5 1-2.5 1-1 2.5-1-2.5-2.5-1 2.5-1z" fill="currentColor" stroke="none"/></svg>',
  HELP: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="8.5"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 .9-1 1.7"/><path d="M12 16.5v.5"/></svg>',
  FOCUS: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4"/><circle cx="12" cy="12" r="2"/></svg>',
  FLAG: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M6 21V4"/><path d="M6 5h11l-2 4 2 4H6"/></svg>',
  BUG: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="M4 5h16v11H9l-5 4z"/><path d="M12 8v4"/><path d="M12 14v.5"/></svg>',
  SIGNOUT: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10 4H6a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h4"/><path d="M15 8l4 4-4 4M19 12H10"/></svg>',
  BRANCH: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="7" cy="5" r="2"/><circle cx="7" cy="19" r="2"/><circle cx="17" cy="7" r="2"/><path d="M7 7v10"/><path d="M17 9c0 5-10 4-10 8"/></svg>',
  SWAP: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8h14l-3-3"/><path d="M20 16H6l3 3"/></svg>',
  PALETTE: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3a9 9 0 1 0 0 18c1.5 0 2-1 2-2 0-1.5-1-2-1-3s1-2 2.500-2H18a3 3 0 0 0 3-3c0-4.500-4-8-9-8z"/><path d="M7.500 11v.5M10 7v.5M15 7v.5"/></svg>'
};
/* substitute __NAME__ placeholders */
document.body.innerHTML = document.body.innerHTML.replace(/__([A-Z]+)__/g, (m, k) => ICONS[k] || m);

