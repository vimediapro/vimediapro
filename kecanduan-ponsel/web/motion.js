/* Motion graphics: "Mengapa anak kecil bisa kecanduan ponsel?"
 * Flat, clean 2D canvas animation driven by a single time value so every
 * frame is deterministic.  window.render(t) draws frame at t seconds.
 * window.TIMELINE (scene + word timings from tools/voice.py) must be set
 * before this script runs.
 */
(() => {
const cv = document.getElementById('c');
const ctx = cv.getContext('2d');
const W = 1080, H = 1920, CX = 540;
const TL = window.TIMELINE;

// ---------- palette ----------
const C = {
  ink: '#16181D', ink2: '#3A3F4A', mute: '#8A8F99', paper: '#F7F3EA', white: '#FFFFFF',
  lime: '#8DC63F', limeD: '#5F9620', coral: '#FF5A47', coralD: '#D93A2B', sky: '#59B2FF', skyD: '#2E86E0',
  yellow: '#FFD034', purple: '#7C5CFF', purpleD: '#5B3EE0', pink: '#FF9DBB', pinkD: '#E8709A',
  skin: '#F6C9A0', skinD: '#E3A77C', hair: '#2A1E17', tear: '#7CCBFF', grey: '#E2DDD3', grey2: '#C9C3B7',
};

// ---------- math / easing ----------
const cl = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
const P = (t, a, d) => cl((t - a) / d);
const lerp = (a, b, p) => a + (b - a) * p;
const eo = x => 1 - Math.pow(1 - x, 3);
const eo5 = x => 1 - Math.pow(1 - x, 5);
const ei = x => x * x * x;
const eio = x => x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
const eb = x => { const c1 = 1.9, c3 = c1 + 1; return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2); };
const el = x => x <= 0 ? 0 : x >= 1 ? 1 : Math.pow(2, -10 * x) * Math.sin((x * 10 - .75) * (2 * Math.PI / 3)) + 1;
const pop = (t, a, d = .45) => eb(P(t, a, d));
// in/out envelope: rises at a over d, falls at b over d2
const inout = (t, a, b, d = .4, d2 = .35) => Math.min(eo(P(t, a, d)), 1 - ei(P(t, b, d2)));
const rnd = s => { const x = Math.sin(s * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); };

// ---------- timeline helpers ----------
const SC = {}; TL.scenes.forEach((s, i) => { SC[s.id] = s; s.idx = i; });
const nrm = x => x.toLowerCase().replace(/[^0-9a-z]/g, '');
function Wd(id, word, occ = 0, end = false) {   // local time of a spoken word
  const s = SC[id]; let k = 0;
  for (const w of s.words) if (nrm(w.w).startsWith(nrm(word))) { if (k === occ) return (end ? w.e : w.s) - s.start; k++; }
  throw new Error(`word "${word}" not found in ${id}`);
}
const dur = id => SC[id].end - SC[id].start;

// ---------- sfx cue registry (collected once, exported for the audio mix) ----------
const CUES = [];
const cue = (id, local, type, gain = 1) => CUES.push({ t: +(SC[id].start + local).toFixed(3), type, gain });

// ---------- drawing primitives ----------
function rr(x, y, w, h, r) { ctx.beginPath(); ctx.roundRect(x, y, w, h, r); }
function fillRR(x, y, w, h, r, col) { rr(x, y, w, h, r); ctx.fillStyle = col; ctx.fill(); }
function circ(x, y, r, col) { if (r <= 0) return; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fillStyle = col; ctx.fill(); }
function ring(x, y, r, lw, col, a0 = 0, a1 = Math.PI * 2) { ctx.beginPath(); ctx.arc(x, y, r, a0, a1); ctx.lineWidth = lw; ctx.strokeStyle = col; ctx.lineCap = 'round'; ctx.stroke(); }
function line(pts, lw, col, cap = 'round') { ctx.beginPath(); ctx.moveTo(pts[0][0], pts[0][1]); for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]); ctx.lineWidth = lw; ctx.strokeStyle = col; ctx.lineCap = cap; ctx.lineJoin = 'round'; ctx.stroke(); }
function T(x, y, s, r, a, fn) {
  if (s <= 0.001 || a <= 0.001) return;
  ctx.save(); ctx.translate(x, y); if (r) ctx.rotate(r); if (s !== 1) ctx.scale(s, s); ctx.globalAlpha *= cl(a); fn(); ctx.restore();
}
function font(w, px, fam = 'J') { ctx.font = `${w} ${px}px ${fam}`; }
function text(str, x, y, col, w = 800, px = 60, align = 'left', fam = 'J', ls = 0) {
  font(w, px, fam); ctx.fillStyle = col; ctx.textAlign = align; ctx.textBaseline = 'alphabetic';
  ctx.letterSpacing = ls + 'px'; ctx.fillText(str, x, y); ctx.letterSpacing = '0px';
}
function tw(str, w, px, fam = 'J', ls = 0) { font(w, px, fam); ctx.letterSpacing = ls + 'px'; const m = ctx.measureText(str).width; ctx.letterSpacing = '0px'; return m; }

// ---------- icons ----------
function heart(x, y, s, col) { T(x, y, s / 100, 0, 1, () => { ctx.beginPath(); ctx.moveTo(0, 38); ctx.bezierCurveTo(-75, -5, -45, -62, 0, -24); ctx.bezierCurveTo(45, -62, 75, -5, 0, 38); ctx.fillStyle = col; ctx.fill(); }); }
function star(x, y, r, col, rot = 0) {
  T(x, y, 1, rot, 1, () => {
    ctx.beginPath();
    for (let i = 0; i < 10; i++) { const a = -Math.PI / 2 + i * Math.PI / 5, rr2 = i % 2 ? r * .48 : r; ctx.lineTo(Math.cos(a) * rr2, Math.sin(a) * rr2); }
    ctx.closePath(); ctx.fillStyle = col; ctx.fill(); ctx.lineJoin = 'round'; ctx.lineWidth = r * .18; ctx.strokeStyle = col; ctx.stroke();
  });
}
function smiley(x, y, r, col = C.yellow) {
  circ(x, y, r, col); circ(x - r * .34, y - r * .18, r * .12, C.ink); circ(x + r * .34, y - r * .18, r * .12, C.ink);
  ctx.beginPath(); ctx.arc(x, y + r * .08, r * .45, .15 * Math.PI, .85 * Math.PI); ctx.lineWidth = r * .13; ctx.strokeStyle = C.ink; ctx.lineCap = 'round'; ctx.stroke();
}
function sadface(x, y, r, col = C.sky) {
  circ(x, y, r, col); circ(x - r * .34, y - r * .15, r * .11, C.ink); circ(x + r * .34, y - r * .15, r * .11, C.ink);
  ctx.beginPath(); ctx.arc(x, y + r * .55, r * .38, 1.2 * Math.PI, 1.8 * Math.PI); ctx.lineWidth = r * .12; ctx.strokeStyle = C.ink; ctx.lineCap = 'round'; ctx.stroke();
  ctx.beginPath(); ctx.ellipse(x + r * .42, y + r * .12, r * .08, r * .13, 0, 0, Math.PI * 2); ctx.fillStyle = '#E8F6FF'; ctx.fill();
}
function note(x, y, s, col) {
  T(x, y, s / 100, -.12, 1, () => {
    ctx.fillStyle = col;
    ctx.beginPath(); ctx.ellipse(-30, 30, 20, 15, -.4, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(30, 18, 20, 15, -.4, 0, Math.PI * 2); ctx.fill();
    ctx.fillRect(-14, -40, 9, 70); ctx.fillRect(46, -52, 9, 70);
    ctx.beginPath(); ctx.moveTo(-14, -40); ctx.lineTo(55, -52); ctx.lineTo(55, -34); ctx.lineTo(-14, -22); ctx.fill();
  });
}
function playIcon(x, y, r, col, fg = C.white) {
  circ(x, y, r, col); ctx.beginPath(); ctx.moveTo(x - r * .28, y - r * .38); ctx.lineTo(x + r * .45, y); ctx.lineTo(x - r * .28, y + r * .38); ctx.closePath();
  ctx.fillStyle = fg; ctx.lineJoin = 'round'; ctx.lineWidth = r * .12; ctx.strokeStyle = fg; ctx.fill(); ctx.stroke();
}
function bolt(x, y, s, col) { T(x, y, s / 100, 0, 1, () => { ctx.beginPath(); ctx.moveTo(10, -50); ctx.lineTo(-30, 8); ctx.lineTo(-2, 8); ctx.lineTo(-12, 50); ctx.lineTo(30, -10); ctx.lineTo(2, -10); ctx.closePath(); ctx.fillStyle = col; ctx.lineJoin = 'round'; ctx.lineWidth = 8; ctx.strokeStyle = col; ctx.fill(); ctx.stroke(); }); }
function checkMark(x, y, s, p, col = C.white) {
  if (p <= 0) return; const pts = [[-.42, .02], [-.12, .32], [.45, -.3]].map(([a, b]) => [x + a * s, y + b * s]);
  const L1 = Math.hypot(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1]), L2 = Math.hypot(pts[2][0] - pts[1][0], pts[2][1] - pts[1][1]);
  const d = p * (L1 + L2); const out = [pts[0]];
  if (d <= L1) out.push([lerp(pts[0][0], pts[1][0], d / L1), lerp(pts[0][1], pts[1][1], d / L1)]);
  else { out.push(pts[1]); const q = (d - L1) / L2; out.push([lerp(pts[1][0], pts[2][0], q), lerp(pts[1][1], pts[2][1], q)]); }
  line(out, s * .16, col);
}
function clockIcon(x, y, r, p, col = C.lime) {
  circ(x, y, r, C.white);
  if (p > 0) { ctx.beginPath(); ctx.moveTo(x, y); ctx.arc(x, y, r * .82, -Math.PI / 2, -Math.PI / 2 + p * Math.PI * 2); ctx.closePath(); ctx.fillStyle = col; ctx.fill(); }
  ring(x, y, r, r * .12, C.ink);
  for (let i = 0; i < 12; i++) { const a = i * Math.PI / 6; line([[x + Math.cos(a) * r * .72, y + Math.sin(a) * r * .72], [x + Math.cos(a) * r * .8, y + Math.sin(a) * r * .8]], r * .05, C.ink); }
  const a = -Math.PI / 2 + p * Math.PI * 2;
  line([[x, y], [x + Math.cos(a) * r * .62, y + Math.sin(a) * r * .62]], r * .09, C.ink);
  line([[x, y], [x, y - r * .4]], r * .1, C.ink); circ(x, y, r * .09, C.ink);
}
function person(x, y, s, col) { circ(x, y - s * .55, s * .3, col); rr(x - s * .42, y - s * .2, s * .84, s * .75, [s * .4, s * .4, s * .12, s * .12]); ctx.fillStyle = col; ctx.fill(); }
function pacifier(x, y, s) {
  T(x, y, s / 100, 0, 1, () => {
    ring(0, -78, 38, 14, C.coralD);
    ctx.beginPath(); ctx.ellipse(0, -10, 92, 52, 0, 0, Math.PI * 2); ctx.fillStyle = C.coral; ctx.fill();
    circ(-48, -12, 10, '#FFC2B8'); circ(48, -12, 10, '#FFC2B8');
    fillRR(-26, 26, 52, 22, 8, C.coralD);
    ctx.beginPath(); ctx.ellipse(0, 70, 30, 34, 0, 0, Math.PI * 2); ctx.fillStyle = '#FFE1B3'; ctx.fill();
  });
}
function molecule(x, y, s, col = C.purple) {
  T(x, y, s / 100, 0, 1, () => {
    circ(0, 0, 58, C.white);
    ctx.beginPath(); for (let i = 0; i < 6; i++) { const a = i * Math.PI / 3 + Math.PI / 6; ctx.lineTo(Math.cos(a) * 26, Math.sin(a) * 26); } ctx.closePath();
    ctx.lineWidth = 7; ctx.strokeStyle = col; ctx.lineJoin = 'round'; ctx.stroke();
    ring(0, 0, 12, 5, col);
    line([[22, -13], [40, -24]], 7, col); line([[-22, -13], [-40, -24]], 7, col); line([[0, 26], [0, 44]], 7, col);
  });
}

// ---------- phone ----------
function phone(x, y, w, h, rot, screenCol, content) {
  T(x, y, 1, rot, 1, () => {
    fillRR(-w / 2 + 8, -h / 2 + 14, w, h, w * .16, 'rgba(22,24,29,.14)'); // flat drop shadow
    fillRR(-w / 2, -h / 2, w, h, w * .16, C.ink);
    const b = w * .055, sx = -w / 2 + b, sy = -h / 2 + b, sw = w - 2 * b, sh = h - 2 * b;
    ctx.save(); rr(sx, sy, sw, sh, w * .11); ctx.fillStyle = screenCol; ctx.fill(); ctx.clip();
    ctx.translate(sx, sy); if (content) content(sw, sh); ctx.restore();
    fillRR(-w * .14, -h / 2 + b + 8, w * .28, w * .055, w * .03, C.ink);
  });
}

// ---------- characters ----------
// Kid: origin = head centre. mood: happy | cry | calm | sad
function kid(x, y, s, o = {}) {
  const mood = o.mood || 'happy', t = o.t || 0, shirt = o.shirt || C.sky;
  T(x, y, s, o.rot || 0, o.a ?? 1, () => {
    // torso
    fillRR(-135, 96, 270, 300, [90, 90, 30, 30], shirt);
    fillRR(-40, 96, 80, 26, 13, 'rgba(0,0,0,.08)');
    // arms (behind head if raised)
    const arm = (sx, sy, ex, ey) => { line([[sx, sy], [ex, ey]], 54, shirt); circ(ex, ey, 30, C.skin); };
    if (o.arms === 'up') { const w = Math.sin(t * 22) * 14; arm(-110, 150, -205, 30 + w); arm(110, 150, 205, 30 - w); }
    // head
    circ(-118, 14, 26, C.skin); circ(118, 14, 26, C.skin); circ(-118, 14, 12, C.skinD); circ(118, 14, 12, C.skinD);
    circ(0, 0, 122, C.skin);
    // hair
    ctx.beginPath(); ctx.arc(0, 0, 128, Math.PI * .93, Math.PI * 2.07);
    ctx.lineTo(112, -28);
    const pts = [112, 56, 0, -56, -112];
    for (let i = 0; i < pts.length - 1; i++) ctx.quadraticCurveTo((pts[i] + pts[i + 1]) / 2, -92, pts[i + 1], i % 2 ? -30 : -42);
    ctx.closePath(); ctx.fillStyle = o.hair || C.hair; ctx.fill();
    circ(-12, -132, 16, o.hair || C.hair);
    // cheeks
    ctx.globalAlpha *= .55; circ(-74, 52, 19, '#FF8C8C'); circ(74, 52, 19, '#FF8C8C'); ctx.globalAlpha /= .55;
    // eyes + mouth
    const blink = o.blink ? 1 - Math.max(0, Math.sin(t * 2.3) > .985 ? 1 : 0) : 1;
    if (mood === 'happy') {
      ring(-46, 22, 17, 9, C.ink, Math.PI * 1.15, Math.PI * 1.85); ring(46, 22, 17, 9, C.ink, Math.PI * 1.15, Math.PI * 1.85);
      ctx.beginPath(); ctx.moveTo(-34, 50); ctx.arc(0, 50, 34, 0, Math.PI); ctx.closePath(); ctx.fillStyle = C.ink; ctx.fill();
      ctx.save(); ctx.clip(); circ(0, 86, 20, '#FF7A8A'); ctx.restore();
    } else if (mood === 'cry') {
      line([[-66, 4], [-36, 18], [-66, 30]], 9, C.ink); line([[66, 4], [36, 18], [66, 30]], 9, C.ink);
      const m = 1 + Math.sin(t * 18) * .12;
      ctx.save(); ctx.beginPath(); ctx.ellipse(0, 70, 40, 32 * m, 0, 0, Math.PI * 2); ctx.fillStyle = C.ink; ctx.fill(); ctx.clip();
      circ(0, 104 * 1, 26, '#FF7A8A'); ctx.restore();
      // tears
      for (const sd of [-1, 1]) {
        ctx.beginPath(); ctx.moveTo(sd * 52, 30); ctx.quadraticCurveTo(sd * (66 + Math.sin(t * 9) * 6), 70, sd * 60, 112);
        ctx.lineWidth = 15; ctx.strokeStyle = C.tear; ctx.lineCap = 'round'; ctx.stroke();
        for (let k = 0; k < 3; k++) {
          const ph = (t * 1.7 + k / 3 + (sd > 0 ? .17 : 0)) % 1;
          ctx.globalAlpha *= (1 - ph); circ(sd * (100 + ph * 70), 60 + ph * 160 - Math.sin(ph * Math.PI) * 60, 14 - ph * 5, C.tear); ctx.globalAlpha /= Math.max(.001, (1 - ph));
        }
      }
    } else if (mood === 'calm' || mood === 'sad') {
      ctx.save(); ctx.translate(0, 26); ctx.scale(1, blink);
      circ(-46, 0, 14, C.ink); circ(46, 0, 14, C.ink); circ(-42, -4, 4, C.white); circ(50, -4, 4, C.white);
      ctx.restore();
      if (mood === 'calm') ring(0, 58, 18, 8, C.ink, .2 * Math.PI, .8 * Math.PI);
      else { ring(0, 84, 20, 8, C.ink, 1.2 * Math.PI, 1.8 * Math.PI); line([[-66, -14], [-30, -6]], 7, C.ink); line([[66, -14], [30, -6]], 7, C.ink); }
    }
    if (o.arms === 'phone' || o.arms === 'phoneUp') {
      // screen glow on the face
      phone(0, 262, 150, 236, 0, o.screen || C.sky, o.screenFn);
      line([[-110, 150], [-80, 290]], 54, shirt); line([[110, 150], [80, 290]], 54, shirt);
      circ(-78, 288, 30, C.skin); circ(78, 288, 30, C.skin);
    } else if (o.arms !== 'up') {
      line([[-118, 150], [-150, 330]], 54, shirt); line([[118, 150], [150, 330]], 54, shirt);
      circ(-150, 336, 30, C.skin); circ(150, 336, 30, C.skin);
    }
  });
}
function adult(x, y, s, o = {}) {
  T(x, y, s, 0, o.a ?? 1, () => {
    fillRR(-62, 250, 50, 210, 25, C.ink2); fillRR(12, 250, 50, 210, 25, C.ink2);
    fillRR(-105, 70, 210, 230, [80, 80, 26, 26], o.shirt || C.lime);
    line([[-95, 120], [-140, 260]], 46, o.shirt || C.lime); circ(-140, 266, 26, C.skin);
    line([[95, 120], [o.hx ?? 150, o.hy ?? 250]], 46, o.shirt || C.lime); circ(o.hx ?? 150, o.hy ?? 256, 26, C.skin);
    circ(0, 0, 92, C.skin);
    ctx.beginPath(); ctx.arc(0, 0, 97, Math.PI * .95, Math.PI * 2.05); ctx.quadraticCurveTo(40, -70, -90, -10); ctx.closePath(); ctx.fillStyle = '#3B2A20'; ctx.fill();
    ring(-34, 14, 12, 7, C.ink, Math.PI * 1.15, Math.PI * 1.85); ring(34, 14, 12, 7, C.ink, Math.PI * 1.15, Math.PI * 1.85);
    ring(0, 36, 22, 7, C.ink, .15 * Math.PI, .85 * Math.PI);
    ctx.globalAlpha *= .5; circ(-56, 38, 13, '#FF8C8C'); circ(56, 38, 13, '#FF8C8C'); ctx.globalAlpha /= .5;
  });
}
// adult hand reaching in from the side (dir = -1 from right, 1 from left)
function reachHand(x, y, dir, o = {}) {
  T(x, y, 1, 0, 1, () => {
    ctx.scale(dir, 1);
    fillRR(-900, -48, 820, 96, 48, o.sleeve || C.coral);
    fillRR(-110, -52, 40, 104, 14, '#FFFFFF');
    fillRR(-80, -55, 120, 110, 44, C.skin);
    if (o.grab) { fillRR(10, -50, 60, 40, 20, C.skin); fillRR(10, 10, 60, 40, 20, C.skin); }
    else { for (let i = 0; i < 4; i++) fillRR(20, -48 + i * 26, 78, 24, 12, C.skin); }
    fillRR(-30, -78, 70, 34, 17, C.skinD);
  });
}

// ---------- brain ----------
const BRAIN = [[-120, -30, 100], [-20, -85, 105], [95, -60, 100], [150, 30, 80], [-150, 55, 78], [0, 10, 125], [-70, 85, 80], [70, 85, 80]];
function brainPath() { ctx.beginPath(); for (const [x, y, r] of BRAIN) { ctx.moveTo(x + r, y); ctx.arc(x, y, r, 0, Math.PI * 2); } }
function brain(x, y, s, o = {}) {
  T(x, y, s, o.rot || 0, o.a ?? 1, () => {
    fillRR(20, 120, 60, 120, 30, C.pinkD);                  // stem
    circ(140, 128, 62, '#F58AAE');                           // cerebellum
    brainPath(); ctx.fillStyle = o.col || C.pink; ctx.fill();
    if (o.front > 0) {                                       // prefrontal highlight (front = left)
      ctx.save(); brainPath(); ctx.clip();
      ctx.globalAlpha *= o.front; ctx.fillStyle = C.lime;
      ctx.beginPath(); ctx.moveTo(-260, -260); ctx.lineTo(-55, -260); ctx.quadraticCurveTo(-110, 0, -30, 260); ctx.lineTo(-260, 260); ctx.fill();
      ctx.restore();
    }
    if (o.glow > 0) { ctx.save(); brainPath(); ctx.clip(); ctx.globalAlpha *= o.glow * .5; ctx.fillStyle = C.yellow; ctx.fillRect(-300, -300, 600, 600); ctx.restore(); }
    // sulci
    ctx.lineWidth = 11; ctx.strokeStyle = 'rgba(160,30,80,.28)'; ctx.lineCap = 'round';
    const S = [[[-170, -10], [-120, -40], [-90, 0], [-40, -30]], [[-60, -120], [-20, -60], [30, -100], [60, -50]], [[110, -100], [90, -40], [150, -10]],
      [[-160, 70], [-110, 40], [-60, 70]], [[-30, 40], [10, 80], [60, 40], [110, 70]], [[20, -10], [70, -20], [110, 10]]];
    for (const s2 of S) { ctx.beginPath(); ctx.moveTo(...s2[0]); for (let i = 1; i < s2.length; i++) { const [a, b] = s2[i - 1], [c, d] = s2[i]; ctx.quadraticCurveTo(a, b, (a + c) / 2, (b + d) / 2); } ctx.lineTo(...s2[s2.length - 1]); ctx.stroke(); }
  });
}

// ---------- layout chrome ----------
function bgFill(col) {
  ctx.fillStyle = col; ctx.fillRect(0, 0, W, H);
  ctx.fillStyle = 'rgba(22,24,29,.055)';
  for (let y = 40; y < H; y += 60) for (let x = 30 + ((y / 60) % 2) * 30; x < W; x += 60) { ctx.beginPath(); ctx.arc(x, y, 3, 0, Math.PI * 2); ctx.fill(); }
}
function headline(t, l1, l2, idx) {
  const x = 80;
  // index chip
  const p0 = eo(P(t, .05, .4));
  T(x, 150, 1, 0, p0, () => {
    const s = `${String(idx).padStart(2, '0')}/09`; const w = tw(s, 700, 26, 'M', 1) + 32;
    fillRR(-(1 - p0) * 40, -34, w, 46, 23, C.ink); text(s, 16 - (1 - p0) * 40, -2, C.white, 700, 26, 'left', 'M', 1);
  });
  const L = [[l1, C.ink, .12, 250], [l2, C.coral, .26, 330]];
  for (const [s, col, a, y] of L) {
    const p = eo5(P(t, a, .6)); if (p <= 0) continue;
    ctx.save(); ctx.beginPath(); ctx.rect(x - 10, y - 74, 1000, 96); ctx.clip();
    text(s, x, y + (1 - p) * 96, col, 800, 68, 'left', 'J', -1.5); ctx.restore();
  }
}
function tagPill(t, a, str, col = C.coral, y = 1408, out = 1e9) {
  const p = eb(P(t, a, .4)) * (1 - eo(P(t, out, .3))); if (p <= 0.001) return;
  const w = tw(str, 700, 27, 'M', 1.5) + 44;
  T(CX, y, 1, 0, cl(p * 2), () => {
    ctx.scale(lerp(.6, 1, cl(p)), 1);
    fillRR(-w / 2, -26, w, 52, 12, col); text(str, 0, 10, C.white, 700, 27, 'center', 'M', 1.5);
  });
}
function bubbleLabel(x, y, str, p, col = C.ink, fg = C.white, px = 36) {
  if (p <= 0.001) return; const w = tw(str, 800, px) + 48;
  T(x, y, p, 0, cl(p * 2), () => { fillRR(-w / 2, -px * .95, w, px * 1.9, px * .95, col); text(str, 0, px * .36, fg, 800, px, 'center'); });
}

// ---------- captions (karaoke style) ----------
let CAP = null;
const buildCap = () => {
  const chunks = []; const PX = 50, MAXW = 820;
  for (const s of TL.scenes) {
    let cur = [];
    const flush = () => { if (cur.length) chunks.push(cur); cur = []; };
    for (const w of s.words) {
      const trial = cur.concat([w]); const width = tw(trial.map(x => x.w).join(' '), 800, PX);
      if (cur.length && (width > MAXW || cur.length >= 4)) flush();
      cur.push(w); if (/[.,?!:]$/.test(w.w) && cur.length >= 2) flush();
    }
    flush();
  }
  chunks.forEach((c, i) => {
    c.start = c[0].s - .05; const nx = chunks[i + 1];
    c.end = nx ? Math.min(nx[0].s - .05, c[c.length - 1].e + .7) : c[c.length - 1].e + .8;
  });
  return { chunks, PX };
};
function captions(tg) {
  if (!CAP) CAP = buildCap();
  const c = CAP.chunks.find(c => tg >= c.start && tg < c.end); if (!c) return;
  const PX = CAP.PX, gap = 16, padX = 30, h = 92, y = 1525;
  font(800, PX); const ws = c.map(w => ctx.measureText(w.w).width);
  const total = ws.reduce((a, b) => a + b, 0) + gap * (c.length - 1) + padX * 2;
  const p = eb(P(tg, c.start, .22)); const a = 1 - P(tg, c.end - .12, .12);
  T(CX, y, lerp(.86, 1, p), 0, a, () => {
    fillRR(-total / 2 + 6, -h / 2 + 8, total, h, 26, 'rgba(22,24,29,.18)');
    fillRR(-total / 2, -h / 2, total, h, 26, C.ink);
    let x = -total / 2 + padX;
    c.forEach((w, i) => {
      const next = c[i + 1];
      const on = tg >= w.s - .03 && (next ? tg < next.s - .03 : true);
      const done = tg >= w.s - .03;
      if (on) { const q = eb(P(tg, w.s - .03, .16)); T(x + ws[i] / 2, 0, lerp(.7, 1, q), 0, 1, () => fillRR(-ws[i] / 2 - 10, -36, ws[i] + 20, 72, 14, C.yellow)); }
      text(w.w, x, PX * .36, on ? C.ink : (done ? C.white : 'rgba(255,255,255,.55)'), 800, PX);
      x += ws[i] + gap;
    });
  });
}

// =====================================================================
// SCENES — draw(t) with t = local seconds
// =====================================================================
const scenes = [];
function scene(id, bg, trans, draw, cues) { const s = SC[id]; scenes.push({ id, bg, trans, draw, s }); if (cues) cues(); }

// 1. HOOK -------------------------------------------------------------
{
  const tTake = Wd('hook', 'diambil') - .35, tCry = tTake + .55, tTitle = Wd('hook', 'Kenapa') - .1;
  scene('hook', '#FFE9B0', 'circle', t => {
    // kid moves down & shrinks when title arrives
    const pT = eio(P(t, tTitle, .7));
    const kx = CX, ky = lerp(860, 1020, pT), ks = lerp(1.25, .95, pT);
    const crying = t > tCry;
    const shake = crying ? Math.sin(t * 40) * 6 * (1 - P(t, tCry + 1.2, 1)) : 0;
    // intro pop
    const pIn = pop(t, .05, .6);
    // burst lines behind crying kid
    if (crying) {
      const pb = eo(P(t, tCry, .4));
      T(kx, ky, ks, t * .3, 1, () => { for (let i = 0; i < 16; i++) { const a = i * Math.PI / 8; ctx.globalAlpha = .9; line([[Math.cos(a) * 250 * pb, Math.sin(a) * 250 * pb], [Math.cos(a) * (250 + 90 * pb), Math.sin(a) * (250 + 90 * pb)]], 14, i % 2 ? C.coral : C.yellow); } });
    }
    const screenFn = (w, h) => {
      ctx.fillStyle = '#FFD0DF'; ctx.fillRect(0, 0, w, h * .55); playIcon(w / 2, h * .28, 28, C.coral);
      for (let i = 0; i < 3; i++) fillRR(14, h * .62 + i * 26, w - 28 - i * 22, 14, 7, 'rgba(255,255,255,.8)');
    };
    T(kx + shake, ky, ks * pIn, 0, 1, () => {
      if (!crying) kid(0, 0, 1, { mood: t > tTake ? 'calm' : 'happy', arms: t > tTake ? 'none' : 'phone', screenFn, t, shirt: C.sky });
      else kid(0, 0, 1, { mood: 'cry', arms: 'up', t, shirt: C.sky });
    });
    // grabbing hand takes phone away to the right
    const hIn = eo(P(t, tTake - .45, .45)), hOut = eio(P(t, tTake, .55));
    if (hIn > 0 && hOut < 1) {
      const px = kx + 330 - hIn * 230 + hOut * 700, py = ky + 262 * ks;
      if (t > tTake) phone(px - 20, py, 150 * ks, 236 * ks, hOut * .5, C.sky, screenFn);
      reachHand(px + 120 + (t > tTake ? 0 : 0), py, -1, { grab: t > tTake });
    }
    // "!!" bubble
    const pX = pop(t, tCry + .1, .4) * (1 - P(t, tTitle, .3));
    T(kx + 250, ky - 220, pX, -.15, 1, () => { fillRR(-70, -60, 140, 120, 30, C.coral); text('!!', 0, 30, C.white, 800, 86, 'center'); });
    // headline / title
    if (t < tTitle + .3) {
      T(0, 0, 1, 0, 1 - P(t, tTitle, .3), () => headline(t, 'Ponsel diambil...', 'langsung tantrum!', 1));
    }
    if (t > tTitle) {
      const L = [['MENGAPA ANAK', C.ink], ['KECIL BISA', C.ink], ['KECANDUAN', C.coral], ['PONSEL?', C.coral]];
      L.forEach(([s, col], i) => {
        const p = eo5(P(t, tTitle + i * .1, .55)); if (p <= 0) return;
        ctx.save(); ctx.beginPath(); ctx.rect(0, 190 + i * 112, W, 116); ctx.clip();
        text(s, CX, 290 + i * 112 + (1 - p) * 116, col, 800, 108, 'center', 'J', -3); ctx.restore();
      });
      const pu = eo(P(t, tTitle + .5, .5)); fillRR(CX - 300 * pu, 650, 600 * pu, 14, 7, C.lime);
    }
    tagPill(t, tCry, 'PONSEL DIAMBIL = TANTRUM', C.coral, 1408, tTitle);
  }, () => { cue('hook', .05, 'pop'); cue('hook', tTake - .45, 'whoosh', .7); cue('hook', tTake, 'snatch'); cue('hook', tCry, 'boing'); cue('hook', tCry + .1, 'pop', .8); cue('hook', tTitle, 'impact'); cue('hook', tTitle + .5, 'swoosh', .5); });
}

// 2. DATA -------------------------------------------------------------
{
  const D = 'data', tNum = Wd(D, '42') - .25, tGrid = Wd(D, 'Hampir') - .1;
  scene(D, '#DCEBFF', 'slide', t => {
    headline(t, 'Data BPS 2025:', 'anak usia dini & ponsel', 2);
    const cy = 720, R = 200;
    const pd = pop(t, .25, .6);
    T(CX, cy, pd, 0, 1, () => {
      circ(0, 0, R + 58, C.white);
      ring(0, 0, R, 74, '#E4ECF8');
      const p = eio(P(t, tNum, 1.6)) * .4225;
      if (p > 0) ring(0, 0, R, 74, C.coral, -Math.PI / 2, -Math.PI / 2 + p * Math.PI * 2);
      const n = Math.round(eio(P(t, tNum, 1.6)) * 42);
      text(`${n}%`, 0, 40, C.ink, 800, 150, 'center', 'J', -4);
      text('anak usia dini', 0, 100, C.mute, 700, 34, 'center');
    });
    // phone badge orbiting the donut
    const pb = pop(t, tNum + 1.4, .5);
    T(CX + 230, cy - 210, pb, .2, 1, () => phone(0, 0, 120, 190, 0, C.coral, (w, h) => { playIcon(w / 2, h / 2, 24, C.white, C.coral); }));
    // 10 people grid: 4.2 filled
    const gy = 1150;
    for (let i = 0; i < 10; i++) {
      const row = Math.floor(i / 5), col = i % 5;
      const y = gy + row * 140 - 60, a = tGrid + i * .06;
      const s = pop(t, a, .4);
      T(CX + (col - 2) * 172, y, s, 0, 1, () => {
        ctx.save(); person(0, 0, 100, C.grey2); ctx.restore();
        const fillAmt = cl(4.225 - i);
        if (fillAmt > 0 && t > tGrid + .6) {
          const q = eo(P(t, tGrid + .6 + i * .12, .4)) * fillAmt;
          ctx.save(); ctx.beginPath(); ctx.rect(-60, -100, 120 * q, 200); ctx.clip(); person(0, 0, 100, C.coral); ctx.restore();
        }
      });
    }
    bubbleLabel(CX, 1340, '≈ 1 dari 2 anak!', pop(t, tGrid + 1.3, .45), C.ink, C.yellow, 40);
    tagPill(t, tNum + .2, 'SUMBER: BPS · PROFIL ANAK USIA DINI 2025', C.skyD, 1408, tGrid + 1.1);
  }, () => { cue(D, .25, 'pop'); cue(D, tNum, 'count', 1); cue(D, tNum + 1.6, 'ding', .9); cue(D, tNum + 1.4, 'pop', .6); for (let i = 0; i < 10; i++) cue(D, tGrid + i * .06, 'tick', .35); cue(D, tGrid + 1.3, 'pop'); });
}

// 3. DOPAMIN ----------------------------------------------------------
{
  const D = 'dopamin', tBrain = .3, tV = Wd(D, 'Video') - .2, tS = Wd(D, 'suara') - .2, tW = Wd(D, 'warna') - .2,
    tDop = Wd(D, 'memicu'), tLagi = Wd(D, 'minta') - .1, tLagi2 = Wd(D, 'lagi', 1);
  const shots = [[tV, 'play'], [tS, 'note'], [tW, 'star']];
  scene(D, '#FFE3EC', 'circle', t => {
    headline(t, 'Dopamin:', 'zat kimia rasa senang', 3);
    const bx = CX, by = 700;
    let glow = 0; for (const [a] of shots) glow = Math.max(glow, 1 - P(t, a + .55, .6)) * (t > a + .5 ? 1 : 0);
    const pulse = shots.reduce((m, [a]) => m + Math.max(0, Math.sin(cl((t - a - .55) / .35) * Math.PI)) * .06, 0);
    // loop arrows "lagi dan lagi"
    const pl = eo(P(t, tLagi, .5));
    if (pl > 0) T(bx, by, 1, t * 2.2, pl, () => { for (let k = 0; k < 2; k++) { ring(0, 0, 320, 18, C.purple, k * Math.PI + .3, k * Math.PI + .3 + 2.3 * pl); const a = k * Math.PI + .3 + 2.3 * pl; T(Math.cos(a) * 320, Math.sin(a) * 320, 1, a + Math.PI / 2, 1, () => { ctx.beginPath(); ctx.moveTo(-26, -18); ctx.lineTo(10, 0); ctx.lineTo(-26, 18); ctx.lineTo(-26, -18); ctx.fillStyle = C.purple; ctx.fill(); ctx.lineJoin = 'round'; ctx.lineWidth = 10; ctx.strokeStyle = C.purple; ctx.stroke(); }); } });
    brain(bx, by, pop(t, tBrain, .6) * (1.05 + pulse), { glow: glow * .9, a: 1 });
    // phone at bottom shooting icons into brain
    const pp = pop(t, tBrain + .3, .55);
    const phx = CX, phy = 1180;
    T(phx, phy, pp, 0, 1, () => phone(0, 0, 220, 300, 0, C.ink2, (w, h) => {
      const k = shots.filter(([a]) => t > a).length;
      const cols = [C.coral, C.sky, C.yellow, C.lime];
      ctx.fillStyle = cols[k % 4]; ctx.fillRect(0, 0, w, h);
      if (k === 1) playIcon(w / 2, h / 2, 40, C.white, cols[1]); else if (k === 2) note(w / 2, h / 2, 90, C.white); else if (k === 3) star(w / 2, h / 2, 46, C.white); else smiley(w / 2, h / 2, 46);
    }));
    shots.forEach(([a, kind], i) => {
      const p = P(t, a, .6); if (p <= 0 || p >= 1) return;
      const e = eio(p); const x = lerp(phx, bx + (i - 1) * 120, e), y = lerp(phy - 120, by + 20, e) - Math.sin(e * Math.PI) * 140;
      const sc = lerp(.8, 1.2, Math.sin(e * Math.PI)) * (1 - ei(P(p, .85, .15)));
      T(x, y, sc, (1 - e) * 1.5, 1, () => { if (kind === 'play') playIcon(0, 0, 52, C.coral); else if (kind === 'note') { circ(0, 0, 56, C.sky); note(0, 0, 70, C.white); } else { circ(0, 0, 56, C.yellow); star(0, 0, 32, C.white); } });
    });
    // dopamine molecules popping out of brain
    for (let i = 0; i < 6; i++) {
      const a = tDop + i * .28; const p = P(t, a, 1.6); if (p <= 0) continue;
      const ang = -Math.PI / 2 + (i - 2.5) * .55; const r = 250 + eo(p) * 120;
      const s = pop(t, a, .4) * (1 - P(t, a + 3.2, .4));
      T(bx + Math.cos(ang) * r * 1.15, by + Math.sin(ang) * r * .75 + Math.sin(t * 3 + i) * 10, s * .8, Math.sin(t * 2 + i) * .3, 1, () => molecule(0, 0, 100, i % 2 ? C.purple : C.coral));
    }
    // dopamine meter (right)
    const pm = pop(t, tDop - .2, .5);
    T(960, 760, pm, 0, 1, () => {
      fillRR(-36, -230, 72, 460, 36, C.white);
      const lvl = cl(shots.filter(([a]) => t > a + .5).length / 3 * .6 + eo(P(t, tLagi, 2)) * .4);
      const lv = lvl + Math.sin(t * 8) * .015 * (lvl > 0);
      fillRR(-24, 218 - 436 * lv, 48, 436 * lv, 24, C.purple);
      text('DOPAMIN', 0, 280, C.purpleD, 700, 24, 'center', 'M', 1);
    });
    bubbleLabel(260, 1010, 'LAGI!', pop(t, tLagi + .3, .4), C.purple, C.white, 44);
    bubbleLabel(820, 1060, 'LAGI!', pop(t, tLagi2 - .05, .4), C.coral, C.white, 44);
    tagPill(t, tDop + .2, 'DOPAMIN ↑ = MAU LAGI', C.purple);
  }, () => { cue(D, tBrain, 'pop'); cue(D, tBrain + .3, 'pop', .6); shots.forEach(([a]) => { cue(D, a, 'whoosh', .55); cue(D, a + .55, 'blip'); }); for (let i = 0; i < 6; i++) cue(D, tDop + i * .28, 'bubble', .45); cue(D, tLagi, 'swoosh', .6); cue(D, tLagi + .3, 'pop'); cue(D, tLagi2 - .05, 'pop'); });
}

// 4. SLOT -------------------------------------------------------------
{
  const D = 'slot', tPull = .5, tSwipe = Wd(D, 'geser') - .15, tQ = Wd(D, 'ditebak') - .3, tUnc = Wd(D, 'ketidakpastian') - .1;
  const SYM = ['heart', 'star', 'smile', 'note', 'bolt', 'play'];
  const spins = [ // start, per-reel stop delays, results
    { a: tPull, d: [1.0, 1.25, 1.5], res: [0, 2, 4] },
    { a: tSwipe, d: [.8, 1.05, 1.3], res: [1, 1, 1] },
    { a: tUnc, d: [.8, 1.05, 1.3], res: [3, 0, 5] },
  ];
  function reelPos(t, r) {
    let pos = 0;
    for (let k = 0; k < spins.length; k++) {
      const sp = spins[k], start = k ? spins[k - 1].res[r] : 4 + r;
      if (t < sp.a) { return k ? spins[k - 1].res[r] : start; }
      const laps = 3 + r; const target = sp.res[r] + 6 * laps;
      const p = P(t, sp.a, sp.d[r]); const e = eb(p) * .0 + (1 - Math.pow(1 - p, 3));
      pos = lerp(start, target, e); if (p < 1) return pos;
    }
    return spins[spins.length - 1].res[r];
  }
  function sym(kind, x, y, s) {
    if (kind === 'heart') heart(x, y + 6, 92 * s, C.coral); else if (kind === 'star') star(x, y, 46 * s, C.yellow);
    else if (kind === 'smile') smiley(x, y, 44 * s); else if (kind === 'note') { note(x, y, 70 * s, C.sky); }
    else if (kind === 'bolt') bolt(x, y, 90 * s, C.purple); else playIcon(x, y, 42 * s, C.lime);
  }
  scene(D, '#E8E1FF', 'slide', t => {
    headline(t, 'Mirip mesin slot:', 'hadiahnya tak terduga', 4);
    const mx = CX, my = 830, pm = pop(t, .1, .6);
    const jack = t > spins[1].a + spins[1].d[2] && t < spins[2].a;
    T(mx, my, pm, 0, 1, () => {
      // lever
      const pull = Math.sin(cl((t - tPull + .3) / .5) * Math.PI);
      const la = -0.25 + pull * 1.2;
      line([[300, 40], [300 + Math.sin(la) * 0 + 40, 40]], 30, C.ink2);
      T(340, 40, 1, la, 1, () => { line([[0, 0], [0, -230]], 22, C.ink2); circ(0, -240, 42, C.coral); circ(-12, -252, 12, '#FF9A8E'); });
      // phone-shaped machine
      phone(0, 0, 560, 820, 0, '#2B2440', (w, h) => {
        // marquee lights
        for (let i = 0; i < 9; i++) circ(40 + i * (w - 80) / 8, 50, 11, (Math.floor(t * 6) + i) % 2 ? C.yellow : '#6B5FA0');
        text(jack ? 'JACKPOT!' : 'SPIN!', w / 2, 140, jack ? C.yellow : C.white, 800, 64, 'center', 'J', 2);
        // reels
        for (let r = 0; r < 3; r++) {
          const rx = 30 + r * ((w - 60) / 3), rw = (w - 60) / 3 - 14, ry = 200, rh = 300;
          ctx.save(); rr(rx, ry, rw, rh, 20); ctx.fillStyle = C.white; ctx.fill(); ctx.clip();
          const pos = reelPos(t, r); const cell = 150;
          for (let k = -2; k <= 2; k++) {
            const idx = Math.floor(pos) + k; const off = (pos - Math.floor(pos));
            const yy = ry + rh / 2 + (k - off) * cell;
            sym(SYM[((idx % 6) + 6) % 6], rx + rw / 2, yy, 1);
          }
          const g = ctx.createLinearGradient(0, ry, 0, ry + rh); g.addColorStop(0, 'rgba(43,36,64,.55)'); g.addColorStop(.25, 'rgba(43,36,64,0)'); g.addColorStop(.75, 'rgba(43,36,64,0)'); g.addColorStop(1, 'rgba(43,36,64,.55)');
          ctx.fillStyle = g; ctx.fillRect(rx, ry, rw, rh); ctx.restore();
        }
        fillRR(30, 342, w - 60, 8, 4, 'rgba(255,90,71,.8)');
        // swipe-up hint
        const ps = P(t, tSwipe - .1, .6);
        if (ps > 0 && ps < 1) { const y = lerp(h - 40, 560, eio(ps)); ctx.globalAlpha = Math.sin(ps * Math.PI); circ(w / 2, y, 40, 'rgba(255,255,255,.35)'); circ(w / 2, y, 22, C.white); ctx.globalAlpha = 1; }
        // bottom bar
        fillRR(40, 560, w - 80, 120, 30, '#3A3157');
        text('GESER  ↑', w / 2, 636, '#B9B0DA', 700, 36, 'center', 'M', 3);
      });
    });
    // finger swipe hand
    const pf = P(t, tSwipe - .4, 1.3);
    if (pf > 0 && pf < 1) { const y = lerp(1300, 1060, eio(P(pf, .25, .5))); T(mx + 60, y, 1, -.2, Math.sin(pf * Math.PI) * 1.4, () => { fillRR(-34, -110, 68, 220, 34, C.skin); fillRR(-60, 40, 120, 160, 40, C.skin); fillRR(-36, -110, 68, 50, [34, 34, 6, 6], C.skinD); }); }
    // jackpot confetti
    const jt = spins[1].a + spins[1].d[2];
    if (t > jt) for (let i = 0; i < 26; i++) {
      const p = P(t, jt, 1.8); if (p >= 1) break;
      const ang = rnd(i) * Math.PI * 2, sp = 300 + rnd(i + 9) * 380;
      const x = mx + Math.cos(ang) * sp * eo(p), y = my - 200 + Math.sin(ang) * sp * eo(p) + p * p * 500;
      T(x, y, 1, p * 8 + i, 1 - p, () => fillRR(-10, -16, 20, 32, 5, [C.coral, C.yellow, C.lime, C.sky, C.purple][i % 5]));
    }
    // question marks = unpredictability
    for (let i = 0; i < 3; i++) {
      const p = pop(t, tQ + i * .15, .4) * (1 - P(t, tUnc + 1.4, .4));
      const pos = [[150, 560], [940, 650], [150, 1130]][i];
      T(pos[0], pos[1] + Math.sin(t * 3 + i) * 12, p, (i - 1) * .25, 1, () => { circ(0, 0, 62, C.ink); text('?', 0, 30, C.yellow, 800, 86, 'center'); });
    }
    tagPill(t, tQ + .3, 'VARIABLE REWARD = SUSAH BERHENTI', C.purpleD);
  }, () => {
    cue(D, .1, 'pop'); cue(D, tPull - .2, 'lever');
    spins.forEach(sp => { cue(D, sp.a, 'spin', 1); sp.d.forEach(d => cue(D, sp.a + d, 'clunk', .7)); });
    cue(D, tSwipe - .1, 'swipe'); cue(D, spins[1].a + spins[1].d[2], 'jackpot');
    for (let i = 0; i < 3; i++) cue(D, tQ + i * .15, 'pop', .6);
  });
}

// 5. AUTOPLAY ---------------------------------------------------------
{
  const D = 'autoplay', tAuto = Wd(D, 'putar') - .2, tInf = Wd(D, 'scroll') - .2, tStop = Wd(D, 'Tidak') - .15, tGone = Wd(D, 'berhenti') - .05;
  const scrollAt = t => { // accelerating scroll
    const a = .3; if (t < a) return 0; const x = t - a; return 120 * x + 260 * x * x * .5;
  };
  scene(D, '#D8F2E2', 'circle', t => {
    headline(t, 'Autoplay & scroll', 'tanpa titik berhenti', 5);
    const px = 400, py = 900;
    T(px, py, pop(t, .05, .6), -.05, 1, () => phone(0, 0, 470, 860, 0, '#F2F4F7', (w, h) => {
      const off = scrollAt(t); const ch = 300; const cols = [C.coral, C.sky, C.yellow, C.purple, C.lime, C.pink];
      const first = Math.floor(off / ch);
      for (let k = first - 1; k < first + 5; k++) {
        const y = k * ch - off + 40; const c = cols[((k % 6) + 6) % 6];
        fillRR(22, y, w - 44, ch - 24, 26, C.white);
        ctx.save(); rr(22, y, w - 44, 170, [26, 26, 0, 0]); ctx.fillStyle = c; ctx.fill(); ctx.restore();
        playIcon(w / 2, y + 85, 36, 'rgba(255,255,255,.9)', c);
        circ(60, y + 214, 22, C.grey); fillRR(96, y + 198, 200, 14, 7, C.grey); fillRR(96, y + 222, 140, 12, 6, C.grey);
      }
      // autoplay toast
      const pa = pop(t, tAuto, .45);
      if (pa > 0) T(w / 2, h - 110, pa, 0, 1, () => {
        fillRR(-180, -50, 360, 100, 50, C.ink);
        const cd = cl(1 - ((t - tAuto) % 1.2) / 1.2);
        ring(-120, 0, 28, 7, '#4A4F5C'); ring(-120, 0, 28, 7, C.lime, -Math.PI / 2, -Math.PI / 2 + (1 - cd) * Math.PI * 2);
        text('Berikutnya...', 30, 12, C.white, 700, 32, 'center');
      });
    }));
    // infinity sign
    const pi = P(t, tInf, 1.1);
    if (pi > 0) T(830, 640, pop(t, tInf, .5), 0, 1, () => {
      circ(0, 0, 150, C.white);
      ctx.beginPath(); const N = 120; const end = Math.floor(N * eo(pi));
      for (let i = 0; i <= end; i++) { const a = i / N * Math.PI * 2; const d = 1 + Math.sin(a) ** 2; ctx.lineTo(95 * Math.cos(a) / d, 95 * Math.sin(a) * Math.cos(a) / d); }
      ctx.lineWidth = 24; ctx.strokeStyle = C.limeD; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.stroke();
    });
    // speed lines along the phone
    if (t > tInf) for (let i = 0; i < 5; i++) { const y = 520 + ((i * 190 - (t - tInf) * 1600) % 900 + 900) % 900; line([[680, y], [680, y + 80]], 10, 'rgba(22,24,29,.25)'); }
    // stop sign swept away
    const ps = pop(t, tStop, .45); const g = eio(P(t, tGone, .7));
    if (ps > 0) T(830 + g * 120, 1020 - g * 900, ps, g * 2.5, 1 - P(t, tGone + .5, .2), () => {
      ctx.beginPath(); for (let i = 0; i < 8; i++) { const a = i * Math.PI / 4 + Math.PI / 8; ctx.lineTo(Math.cos(a) * 130, Math.sin(a) * 130); } ctx.closePath();
      ctx.fillStyle = C.coral; ctx.fill(); ctx.lineWidth = 12; ctx.strokeStyle = C.white; ctx.stroke();
      text('STOP', 0, 26, C.white, 800, 72, 'center');
    });
    tagPill(t, tAuto + .2, 'AUTOPLAY · INFINITE SCROLL', C.limeD);
  }, () => { cue(D, .05, 'pop'); cue(D, .3, 'scroll', 1); cue(D, tAuto, 'ding', .7); cue(D, tInf, 'swoosh', .8); cue(D, tStop, 'thud'); cue(D, tGone, 'whoosh'); });
}

// 6. REM (prefrontal cortex) -------------------------------------------
{
  const D = 'rem', tPFC = Wd(D, 'korteks') - .2, tMat = Wd(D, 'matang') - .2, tRem = Wd(D, 'rem') - .25;
  scene(D, '#FFF0C9', 'slide', t => {
    headline(t, 'Rem di otak anak', 'belum matang', 6);
    const by = 660;
    brain(CX - 20, by, pop(t, .15, .6) * 1.0, { front: eo(P(t, tPFC, .6)) * (.88 + .12 * Math.sin(t * 5)) });
    // pointer label
    const pl = eo(P(t, tPFC + .3, .5));
    if (pl > 0) { line([[CX - 190, by - 40], [CX - 190 - 60 * pl, by - 190 * pl]], 8, C.ink); circ(CX - 190, by - 40, 12, C.ink); }
    bubbleLabel(CX - 160, by - 230, 'Korteks prefrontal', pop(t, tPFC + .5, .45), C.limeD, C.white, 34);
    bubbleLabel(CX + 260, by + 140, 'pengendali diri', pop(t, tPFC + .8, .45), C.ink, C.white, 30);
    // age timeline
    const ty = 1170, x0 = 120, x1 = 960, pT = pop(t, tMat - .5, .5);
    T(0, 0, 1, 0, cl(pT * 2), () => {
      fillRR(x0, ty - 16, x1 - x0, 32, 16, C.white);
      const pf = eio(P(t, tMat, 1.6));
      fillRR(x0, ty - 16, (x1 - x0) * pf, 32, 16, C.lime);
      for (let a = 0; a <= 25; a += 5) { const x = lerp(x0, x1, a / 25); circ(x, ty, 9, C.ink); text(String(a), x, ty + 62, C.ink2, 700, 30, 'center', 'M'); }
      // flag at 25
      const pf2 = pop(t, tMat + 1.5, .45);
      T(x1, ty - 30, pf2, 0, 1, () => { line([[0, 0], [0, -120]], 8, C.ink); ctx.beginPath(); ctx.moveTo(0, -120); ctx.lineTo(-110, -95); ctx.lineTo(0, -70); ctx.fill(); fillRR(-200, -170, 190, 56, 28, C.limeD); text('±25 thn', -105, -131, C.white, 800, 30, 'center'); });
      // kid marker at ~5
      const pk = pop(t, tMat + .3, .45);
      T(lerp(x0, x1, 5 / 25), ty - 120, pk * .32, 0, 1, () => kid(0, 0, 1, { mood: 'calm', arms: 'none', t, shirt: C.coral }));
    });
    // brake battery low
    const pr = pop(t, tRem, .5);
    T(CX, 1325, pr, 0, 1, () => {
      fillRR(-290, -54, 580, 108, 54, C.ink);
      text('REM', -205, 14, C.white, 800, 40, 'center');
      fillRR(-130, -26, 230, 52, 12, '#3A3F4A'); fillRR(102, -12, 14, 24, 4, '#3A3F4A');
      const blink = Math.floor(t * 3) % 2 ? 1 : .35;
      ctx.globalAlpha *= blink; fillRR(-122, -18, 52, 36, 8, C.coral); ctx.globalAlpha /= blink;
      text('LEMAH', 195, 12, C.coral, 800, 28, 'center', 'M');
    });
  }, () => { cue(D, .15, 'pop'); cue(D, tPFC, 'shimmer'); cue(D, tPFC + .5, 'pop'); cue(D, tPFC + .8, 'pop', .6); cue(D, tMat - .5, 'swoosh', .5); cue(D, tMat, 'rise'); cue(D, tMat + 1.5, 'ding', .8); cue(D, tMat + .3, 'pop', .5); cue(D, tRem, 'alert'); });
}

// 7. DOT DIGITAL --------------------------------------------------------
{
  const D = 'dot', tRewel = Wd(D, 'rewel') - .6, tKasih = Wd(D, 'kasih') - .2, tLoop = Wd(D, 'Lama') - .15, tBosan = Wd(D, 'bosan') - .2, tCari = Wd(D, 'cari') - .2;
  scene(D, '#FFE1D9', 'circle', t => {
    headline(t, 'Dot digital:', 'penenang instan', 7);
    // phase A: pacifier -> phone morph
    const pA = pop(t, .1, .5) * (1 - eio(P(t, tRewel - .1, .35)));
    if (pA > 0) T(CX, 820, pA, Math.sin(t * 3) * .08, 1, () => {
      circ(0, 0, 260, C.white);
      const m = eio(P(t, 1.0, .5));
      T(-120 * m, 0, 1 - m * .25, 0, 1, () => pacifier(0, 0, 150));
      T(130, 0, m, .1, 1, () => phone(0, 0, 150, 240, 0, C.sky, (w, h) => playIcon(w / 2, h / 2, 30, C.white, C.sky)));
      if (m > 0) text('=', 15, 26, C.ink, 800, 90, 'center');
    });
    // phase B: crying kid -> phone -> calm
    const pB = pop(t, tRewel, .5) * (1 - eio(P(t, tLoop, .4)));
    const calm = t > tKasih + .45;
    if (pB > 0) T(CX, 800, pB * 1.1, 0, 1, () => {
      if (!calm) kid(0, 0, 1, { mood: 'cry', arms: 'up', t, shirt: C.purple });
      else kid(0, 0, 1, { mood: 'calm', arms: 'phone', t, shirt: C.purple, blink: true, screenFn: (w, h) => { ctx.fillStyle = C.yellow; ctx.fillRect(0, 0, w, h); smiley(w / 2, h / 2, 40); } });
      const hIn = eo(P(t, tKasih - .1, .5)), hOut = eio(P(t, tKasih + .45, .5));
      if (hIn > 0 && hOut < 1) {
        const px = 420 - hIn * 420 + hOut * 900;
        if (!calm) phone(px - 30, 262, 150, 236, 0, C.yellow, (w, h) => smiley(w / 2, h / 2, 40));
        reachHand(px + 90, 262, -1, { grab: true, sleeve: C.lime });
      }
    });
    bubbleLabel(CX + 250, 560, 'diam ✓', pop(t, tKasih + .7, .45) * (1 - eio(P(t, tLoop, .3))), C.limeD, C.white, 36);
    // phase C: loop diagram
    const pC = pop(t, tLoop + .1, .55);
    if (pC > 0) {
      const cy = 860, lx = CX - 230, rx2 = CX + 230;
      T(CX, cy, pC, t * .6, 1, () => { for (let k = 0; k < 2; k++) { const a0 = k * Math.PI + .55, a1 = a0 + Math.PI - 1.1; ring(0, 0, 300, 16, C.coralD, a0, a1); T(Math.cos(a1) * 300, Math.sin(a1) * 300, 1, a1 + Math.PI / 2, 1, () => { ctx.beginPath(); ctx.moveTo(-26, -20); ctx.lineTo(12, 0); ctx.lineTo(-26, 20); ctx.closePath(); ctx.fillStyle = C.coralD; ctx.fill(); }); } });
      const n1 = pop(t, tBosan, .45), n2 = pop(t, tCari, .45);
      T(lx, cy, n1, 0, 1, () => { circ(0, 0, 150, C.white); sadface(0, -20, 70); text('Bosan/sedih', 0, 100, C.ink, 800, 32, 'center'); });
      T(rx2, cy, n2, 0, 1, () => { circ(0, 0, 150, C.white); phone(0, -20, 90, 140, .12, C.coral, (w, h) => playIcon(w / 2, h / 2, 20, C.white, C.coral)); text('Cari layar', 0, 100, C.ink, 800, 32, 'center'); });
      bubbleLabel(CX, cy + 380, 'jadi kebiasaan', pop(t, tCari + .8, .45), C.ink, C.yellow, 36);
    }
    tagPill(t, tLoop + .2, 'BOSAN → LAYAR → ULANGI', C.coralD);
  }, () => { cue(D, .1, 'pop'); cue(D, 1.0, 'morph'); cue(D, tRewel, 'boing', .8); cue(D, tKasih - .1, 'whoosh', .6); cue(D, tKasih + .45, 'blip'); cue(D, tKasih + .7, 'pop'); cue(D, tLoop + .1, 'swoosh', .6); cue(D, tBosan, 'pop'); cue(D, tCari, 'pop'); cue(D, tCari + .8, 'pop', .6); });
}

// 8. WHO -----------------------------------------------------------------
{
  const D = 'who', tC1 = Wd(D, 'bawah') - .35, tC2 = Wd(D, 'Usia') - .2, tJam = Wd(D, 'satu') - .2;
  scene(D, '#DDF0FF', 'slide', t => {
    headline(t, 'Saran WHO:', 'batasi waktu layar', 8);
    // globe badge intro
    const pg = pop(t, .15, .6) * (1 - eio(P(t, tC1 - .35, .35)));
    T(CX, 840, pg, Math.sin(t * 1.5) * .05, 1, () => {
      circ(0, 0, 290, C.white); circ(0, 0, 210, C.sky);
      ctx.save(); ctx.beginPath(); ctx.arc(0, 0, 210, 0, Math.PI * 2); ctx.clip();
      const sp = t * 60;
      for (let k = 0; k < 4; k++) { const x = ((k * 160 + sp) % 640) - 320; ctx.beginPath(); ctx.ellipse(x, -30, 70, 50, .3, 0, Math.PI * 2); ctx.fillStyle = C.lime; ctx.fill(); ctx.beginPath(); ctx.ellipse(x + 60, 90, 45, 60, -.2, 0, Math.PI * 2); ctx.fill(); }
      ctx.restore();
      ring(0, 0, 210, 10, C.skyD); ctx.beginPath(); ctx.ellipse(0, 0, 90, 210, 0, 0, Math.PI * 2); ctx.lineWidth = 8; ctx.strokeStyle = 'rgba(255,255,255,.7)'; ctx.stroke();
      line([[-210, 0], [210, 0]], 8, 'rgba(255,255,255,.7)');
      T(0, 300, pop(t, .6, .45), 0, 1, () => { fillRR(-260, -45, 520, 90, 45, C.ink); text('Organisasi Kesehatan Dunia', 0, 12, C.white, 800, 32, 'center'); });
    });
    // card 1
    const c1 = eo5(P(t, tC1, .6));
    T(CX + (1 - c1) * 1100, 650, 1, 0, 1, () => {
      fillRR(-450, -170, 900, 340, 44, C.white);
      text('USIA', -390, -90, C.mute, 700, 26, 'left', 'M', 2);
      text('< 2 tahun', -390, -10, C.ink, 800, 76, 'left', 'J', -2);
      fillRR(-390, 40, 270, 70, 35, C.coral); text('Tanpa layar', -255, 87, C.white, 800, 32, 'center');
      const ps = pop(t, tC1 + .5, .45);
      T(270, 0, ps, 0, 1, () => {
        circ(0, 0, 130, '#FFE5E1'); phone(0, 0, 110, 180, 0, C.sky, null);
        ring(0, 0, 110, 22, C.coral); line([[-78, 78], [78, -78]], 22, C.coral, 'butt');
      });
    });
    // card 2
    const c2 = eo5(P(t, tC2, .6));
    T(CX - (1 - c2) * 1100, 1060, 1, 0, 1, () => {
      fillRR(-450, -170, 900, 340, 44, C.white);
      text('USIA', -390, -90, C.mute, 700, 26, 'left', 'M', 2);
      text('2–4 tahun', -390, -10, C.ink, 800, 76, 'left', 'J', -2);
      fillRR(-390, 40, 330, 70, 35, C.limeD); text('Maks. 1 jam/hari', -225, 87, C.white, 800, 32, 'center');
      T(270, 0, pop(t, tC2 + .4, .45), 0, 1, () => { circ(0, 0, 130, '#E6F4D5'); clockIcon(0, 0, 100, eio(P(t, tJam, 1.2)) * (1 / 12) * 12 * 0.999 * (1)); });
    });
    tagPill(t, tJam + .4, 'SUMBER: WHO, 2019 · MAKIN SEDIKIT MAKIN BAIK', C.skyD);
  }, () => { cue(D, .15, 'pop'); cue(D, .6, 'pop', .6); cue(D, tC1, 'whoosh'); cue(D, tC1 + .5, 'pop'); cue(D, tC1 + .7, 'error', .8); cue(D, tC2, 'whoosh'); cue(D, tC2 + .4, 'pop'); cue(D, tJam, 'clock', 1); cue(D, tJam + 1.2, 'ding', .8); });
}

// 9. TIPS + closing -------------------------------------------------------
{
  const D = 'tips', tA = Wd(D, 'aturan') - .3, tB = Wd(D, 'temani') - .2, tCc = Wd(D, 'ajak') - .2,
    tK = Wd(D, 'Karena') - .15, tKita = Wd(D, 'Mereka') - .15, Dd = dur(D);
  const items = [[tA, 'Atur waktu layar', C.sky, 'clock'], [tB, 'Temani saat menonton', C.purple, 'eye'], [tCc, 'Ajak main di luar', C.limeD, 'ball']];
  scene(D, '#E6F6D6', 'circle', t => {
    const out1 = eio(P(t, tK - .2, .45));
    T(0, 0, 1, 0, 1 - out1, () => {
      headline(t, 'Yuk, mulai', 'dari rumah!', 9);
      items.forEach(([a, label, col, ic], i) => {
        const p = eo5(P(t, a, .55)); const y = 560 + i * 230;
        T(CX + (1 - p) * -1100, y, 1, 0, 1, () => {
          fillRR(-450, -95, 900, 190, 95, C.white);
          circ(-355, 0, 70, col);
          if (ic === 'clock') clockIcon(-355, 0, 44, .25, C.yellow);
          else if (ic === 'eye') { person(-375, 18, 56, C.white); person(-330, 26, 40, C.yellow); }
          else { circ(-355, 0, 40, C.white); ctx.beginPath(); ctx.arc(-355, 0, 40, 0, Math.PI * 2); ctx.lineWidth = 6; ctx.strokeStyle = col; ctx.stroke(); line([[-395, 0], [-315, 0]], 6, col); ring(-355, -60, 50, 6, col, .7, 2.45); ring(-355, 60, 50, 6, col, 3.85, 5.6); }
          text(label, -255, 16, C.ink, 800, 46, 'left', 'J', -1);
          const pc = P(t, a + .45, .35);
          T(370, 0, pop(t, a + .4, .35), 0, 1, () => { circ(0, 0, 48, C.lime); checkMark(0, 4, 56, pc); });
        });
      });
    });
    // closing statement
    const pK = eo5(P(t, tK, .55)), pKt = eo5(P(t, tKita, .55));
    if (pK > 0) {
      ctx.save(); ctx.beginPath(); ctx.rect(0, 210, W, 120); ctx.clip();
      text('Anak tidak butuh layar.', CX, 300 + (1 - pK) * 120, C.ink, 800, 70, 'center', 'J', -2); ctx.restore();
    }
    if (pKt > 0) {
      ctx.save(); ctx.beginPath(); ctx.rect(0, 330, W, 140); ctx.clip();
      text('Mereka butuh kita.', CX, 438 + (1 - pKt) * 140, C.limeD, 800, 92, 'center', 'J', -3); ctx.restore();
    }
    // family illustration
    const pf = pop(t, tK + .2, .6);
    if (pf > 0) {
      const fy = 860 + Math.sin(t * 2) * 6;
      T(CX, fy, pf, 0, 1, () => {
        circ(0, 220, 380, 'rgba(255,255,255,.7)');
        adult(-120, -40, 1.05, { shirt: C.lime, hx: 120, hy: 300 });
        kid(170, 170, .62, { mood: 'happy', arms: 'none', t, shirt: C.coral });
        // phone dropped on the floor
        T(-330, 430, 1, -1.35, 1, () => phone(0, 0, 90, 150, 0, '#444A57', null));
      });
      for (let i = 0; i < 5; i++) {
        const a = tKita + .2 + i * .18, p = P(t, a, 2.2); if (p <= 0) continue;
        heart(CX + 40 + (rnd(i) - .5) * 420 + Math.sin(p * 9 + i) * 20, 780 - eo(p) * 220, 70 * pop(t, a, .4) * (1 - P(p, .8, .2)), [C.coral, C.pink, C.coral][i % 3]);
      }
    }
    tagPill(t, tKita + .4, 'WAKTU BERSAMA > WAKTU LAYAR', C.coral);
  }, () => {
    items.forEach(([a]) => { cue(D, a, 'whoosh', .6); cue(D, a + .4, 'check'); });
    cue(D, tK - .2, 'swoosh', .6); cue(D, tK + .2, 'pop'); cue(D, tKita, 'impact', .8);
    for (let i = 0; i < 5; i++) cue(D, tKita + .2 + i * .18, 'bubble', .4);
    cue(D, Dd - .55, 'whooshout', 1);
  });
}

// ---------- transitions & master render ----------
const TR = .55;
function drawScene(sc, lt) { ctx.save(); bgFill(sc.bg); sc.draw(lt); ctx.restore(); }
function render(tg) {
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalAlpha = 1;
  let i = scenes.findIndex(sc => tg >= sc.s.start && tg < sc.s.end); if (i < 0) i = scenes.length - 1;
  const sc = scenes[i], lt = tg - sc.s.start;
  if (i > 0 && lt < TR) {
    const prev = scenes[i - 1], p = eio(lt / TR);
    if (sc.trans === 'slide') {
      ctx.save(); ctx.translate(0, -p * H * .35); ctx.globalAlpha = 1; drawScene(prev, tg - prev.s.start); ctx.restore();
      ctx.save(); ctx.translate(0, (1 - p) * H); drawScene(sc, lt);
      fillRR(0, -30, W, 60, 0, sc.bg); ctx.restore();
    } else {
      drawScene(prev, tg - prev.s.start);
      ctx.save(); ctx.beginPath(); ctx.arc(CX, 1000, p * 1350, 0, Math.PI * 2); ctx.clip(); drawScene(sc, lt); ctx.restore();
      // accent ring riding the wipe
      if (p < 1) ring(CX, 1000, p * 1350, 40 * (1 - p), [C.lime, C.coral, C.yellow, C.purple][i % 4]);
    }
  } else drawScene(sc, lt);
  // global chrome
  captions(tg);
  // progress bar
  const prog = cl(tg / TL.length); fillRR(0, 0, W, 10, 0, 'rgba(22,24,29,.12)'); fillRR(0, 0, W * prog, 10, 0, C.lime);
  // watermark
  text('vimediapro', W - 60, H - 70, 'rgba(22,24,29,.45)', 800, 30, 'right', 'J', -.5);
  circ(W - 60 - tw('vimediapro', 800, 30, 'J', -.5) - 22, H - 80, 9, C.lime);
  // fade to outro's dark tone
  const fo = P(tg, TL.length - .45, .45);
  if (fo > 0) { ctx.fillStyle = `rgba(10,14,8,${fo})`; ctx.fillRect(0, 0, W, H); }
}

// scene transition cues
scenes.forEach((sc, i) => { if (i > 0) CUES.push({ t: +sc.s.start.toFixed(3), type: sc.trans === 'slide' ? 'whoosh' : 'swoosh', gain: .8 }); });
CUES.sort((a, b) => a.t - b.t);

window.render = render;
window.CUES = CUES;
window.READY = Promise.all(['500 20px J', '700 20px J', '800 20px J', '700 20px M'].map(f => document.fonts.load(f))).then(() => document.fonts.ready).then(() => true);
})();
