# Changelog

## 0.3.2 (versionCode 5)
### Fixed
- APN: `parse_failed: Could not assemble WAN profile list` — otomatis beralih ke AT Command Fallback (`AT+CGDCONT?`, `AT+CGACT?`, `AT+CGPADDR`, `AT+QICSGP`/`AT+CGAUTH`).
- Network Mode: `Format network mode tidak didukung` — parser mendukung `settings.nr5g_mode` (kode QManager-RM520N), `nr5g_disable_mode` (dokumentasi), wrapper `data`, angka berbentuk string, dan kapitalisasi berbeda.
- Network Mode write via API kini mengirim `nr5g_mode` dan `nr5g_disable_mode` (sebelumnya nilai NR5G diabaikan backend RM520N).

### Added
- Compatibility layer `ModemGateway` (QManager API ↔ AT Command Fallback) dengan indikator Data Source.
- AT fallback write untuk APN (QICSGP/CGDCONT/CGAUTH + registrasi ulang opsional) dan Network Mode (`AT+QNWPREFCFG`), berdasarkan kemampuan firmware yang terdeteksi; tanpa fallback write otomatis setelah POST gagal.
- Penanganan urutan prioritas `LTE:NR5G` / `NR5G:LTE`; opsi NSA/SA dinonaktifkan bila `nr5g_disable_mode` tidak didukung; verifikasi kemampuan 5G (ATI / `AT+QNWPREFCFG=?`).
- Settings > Diagnostics: model, firmware, kompatibilitas endpoint, HTTP status, raw response tersensor, tombol Copy Diagnostic Report.
- Unit test JVM untuk parser APN, Network Mode, AT CSV, identitas modem, dan redaksi; dijalankan di GitHub Actions.

### Unchanged
- SSL/HTTPS self-signed (0.3.1), Dashboard, Band Lock, Cell Lock, SMS, AT Tools, multi profil, desain UI.

## 0.3.1 (versionCode 4)
### Fixed
- HTTPS ke modem dengan sertifikat self-signed gagal dengan `CertPathValidatorException: Trust anchor for certification path not found`.

### Added
- SSL Certificate Mode per profil: *Strict SSL* (default) dan *Trust Self-Signed Certificate*.
- Dialog verifikasi sertifikat (hostname:port, SHA-256, subject, issuer, masa berlaku) sebelum password dikirim.
- Penyimpanan sertifikat tepercaya per hostname + port + fingerprint; pinning fingerprint pada HTTP client profil.
- Toleransi hostname mismatch terbatas: hanya host profil dengan fingerprint yang sudah disetujui.
- Deteksi perubahan fingerprint: koneksi dihentikan dan konfirmasi ulang diminta.
- Tombol **Test Connection** (tanpa login) dan **Reset Trusted Certificate**.
- Pesan error SSL/jaringan yang mudah dipahami.
- `network_security_config.xml` (cleartext untuk LAN tepercaya, trust anchor sistem).

### Unchanged
- Band Lock, Cell Lock, APN, SMS, Network Mode, AT Command, dashboard monitoring, tema/UI, workflow GitHub Actions.

## 0.3.0 (versionCode 3)
- SMS management dan Network Mode.

## 0.2.0
- Band lock, cell lock, APN management.
