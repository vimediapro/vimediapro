# Mengapa Anak Kecil Bisa Kecanduan Ponsel? — Motion graphics 90 detik (1080×1920)

Hasil akhir: `output/Mengapa_Anak_Kecanduan_Ponsel_1080x1920.mp4` (85 dtk konten + 5 dtk outro VIMEDIAPRO).

## Pipeline
| Langkah | Perintah | Hasil |
|---|---|---|
| Voice-over (Edge TTS `id-ID-ArdiNeural`, pria) + timing per kata | `python3 tools/voice.py 85` | `build/voice/*.mp3`, `build/timeline.json` |
| Animasi (Canvas 2D, flat design, dirender headless Chromium) | `node tools/render.js video 4` | `build/video_nosound.mp4`, `build/cues.json` |
| Preview frame | `node tools/render.js stills 3,12.5` | `build/stills/*.png` |
| Backsong + SFX disintesis prosedural, ducking di bawah voice | `python3 tools/audio.py` | `build/mix.wav` |
| Gabung + outro | lihat perintah ffmpeg di bawah | `output/*.mp4` |

Naskah ada di `script.json` (field `tts` = ejaan khusus untuk pengucapan, mis. "We Ha O").
Semua sinkronisasi visual/SFX diambil dari timing kata pada voice-over, jadi mengubah naskah → jalankan ulang semua langkah.

```
ffmpeg -i build/video_nosound.mp4 -i build/mix.wav -i assets/outro.mp4 -filter_complex \
 "[0:v]setsar=1,fps=30,format=yuv420p[v0];[2:v]setsar=1,fps=30,format=yuv420p[v1];[1:a]aformat=channel_layouts=stereo[a0];\
  [2:a]loudnorm=I=-15:TP=-1.5:LRA=11,aresample=48000,aformat=channel_layouts=stereo[a1];[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a]" \
 -map "[v]" -map "[a]" -c:v libx264 -crf 18 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart output/out.mp4
```

## Sumber fakta
- BPS, *Profil Anak Usia Dini 2025*: 42,25% anak usia dini (0–6 th) sudah menggunakan telepon seluler.
- WHO (24 April 2019), *Guidelines on physical activity, sedentary behaviour and sleep for children under 5*: < 2 th tanpa screen time (1 th: tidak ada); 2–4 th maks. 1 jam/hari, makin sedikit makin baik.
- Korteks prefrontal (kontrol diri) matang sekitar pertengahan usia 20-an; mekanisme dopamin & *variable reward*; desain autoplay/infinite scroll.
