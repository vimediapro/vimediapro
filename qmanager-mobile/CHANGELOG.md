# Changelog

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
