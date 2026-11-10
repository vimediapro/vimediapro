// Render the canvas animation to video (or stills) with headless Chromium.
//   node tools/render.js stills 1.2,5,9.5     -> build/stills/t_XX.png
//   node tools/render.js video [workers]      -> build/video_nosound.mp4
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path'), { spawn, execFileSync } = require('child_process');
const ROOT = path.resolve(__dirname, '..'), B = path.join(ROOT, 'build');
const FPS = 30;
const timeline = JSON.parse(fs.readFileSync(path.join(B, 'timeline.json'), 'utf8'));

async function openPage(browser) {
  const page = await browser.newPage({ viewport: { width: 540, height: 960 } });
  page.on('pageerror', e => { console.error('PAGE ERROR', e.message); process.exit(1); });
  page.on('console', m => { if (m.type() === 'error') console.error('console:', m.text()); });
  await page.addInitScript(tl => { window.TIMELINE = tl; }, timeline);
  await page.goto('file://' + path.join(ROOT, 'web', 'index.html'));
  await page.evaluate(() => window.READY);
  return page;
}
const grab = (page, t, type = 'image/jpeg', q = .94) => page.evaluate(([t, type, q]) => { window.render(t); return document.getElementById('c').toDataURL(type, q); }, [t, type, q])
  .then(u => Buffer.from(u.slice(u.indexOf(',') + 1), 'base64'));

(async () => {
  const mode = process.argv[2] || 'video';
  const browser = await chromium.launch();
  if (mode === 'stills') {
    const page = await openPage(browser);
    fs.mkdirSync(path.join(B, 'stills'), { recursive: true });
    for (const t of process.argv[3].split(',').map(Number)) fs.writeFileSync(path.join(B, 'stills', `t_${t.toFixed(2)}.png`), await grab(page, t, 'image/png'));
    fs.writeFileSync(path.join(B, 'cues.json'), JSON.stringify(await page.evaluate(() => window.CUES), null, 1));
  } else {
    const workers = +(process.argv[3] || 4);
    const N = Math.round(timeline.length * FPS);
    const per = Math.ceil(N / workers);
    let done = 0; const t0 = Date.now();
    const segs = [];
    await Promise.all([...Array(workers)].map(async (_, w) => {
      const a = w * per, b = Math.min(N, a + per); if (a >= b) return;
      const out = path.join(B, `seg_${w}.mp4`); segs[w] = out;
      const page = await openPage(browser);
      if (w === 0) fs.writeFileSync(path.join(B, 'cues.json'), JSON.stringify(await page.evaluate(() => window.CUES), null, 1));
      const ff = spawn('ffmpeg', ['-v', 'error', '-y', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-',
        '-c:v', 'libx264', '-preset', 'medium', '-crf', '16', '-pix_fmt', 'yuv420p', '-r', String(FPS), out], { stdio: ['pipe', 'inherit', 'inherit'] });
      for (let f = a; f < b; f++) {
        const buf = await grab(page, f / FPS);
        if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
        if (++done % 150 === 0) console.log(`${done}/${N} frames  ${((Date.now() - t0) / 1000).toFixed(0)}s`);
      }
      ff.stdin.end(); await new Promise(r => ff.on('close', r));
    }));
    fs.writeFileSync(path.join(B, 'segs.txt'), segs.filter(Boolean).map(s => `file '${s}'`).join('\n'));
    execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', path.join(B, 'segs.txt'), '-c', 'copy', path.join(B, 'video_nosound.mp4')]);
    console.log('video done in', ((Date.now() - t0) / 1000).toFixed(0), 's');
  }
  await browser.close();
})();
