# HMDM Installer (Android)

Port dari HMDM ADB Installer v3 (Windows) ke aplikasi Android. HP teknisi menjadi "komputer ADB":
pair + connect lewat Wireless debugging ke HP target, memasang APK, lalu `dpm set-device-owner`.
Tidak butuh adb.exe / PC.

## Cara membangun APK
**Opsi A – Android Studio:** File › Open folder ini › tunggu sync › Build › Build APK(s).
Hasil: `app/build/outputs/apk/debug/app-debug.apk`.

**Opsi B – GitHub Actions (tanpa install apa pun):** upload folder ini ke repo GitHub (private juga bisa),
buka tab Actions › "Build APK" › Run workflow, lalu unduh artifact `HMDM-Installer-apk`.

## Cara pakai
1. Pasang APK ini di HP teknisi (izinkan "install dari sumber tidak dikenal").
2. HP target: Opsi Developer › Wireless debugging (satu Wi-Fi dengan HP teknisi) › "Pair device with pairing code".
3. Isi IP, Port pairing, kode › **Pair**. Lalu isi IP + Port connect (layar utama Wireless debugging) › **Connect**.
4. Daftar APK awalnya kosong: tambahkan `hmdm.apk` (dan APK lain) dengan "+ Tambah APK".
5. **Install Semua APK**, **Set Device Owner**, atau **Jalankan Semua**.

Syarat device owner: HP target baru / habis factory reset dan belum ada akun (Google dll).
Satu HP tidak bisa memasang dirinya sendiri sebagai device owner dengan cara ini kecuali IP diisi 127.0.0.1 dan
syarat di atas terpenuhi.

## Catatan teknis
- ADB memakai library [libadb-android](https://github.com/MuntashirAkon/libadb-android) (JitPack) + Conscrypt (TLS 1.3).
- Kunci/sertifikat ADB dibuat sekali (`AdbManager.kt`) dan disimpan; pairing terikat pada kunci ini.
- Install = `cmd package install -r -S <ukuran>` lewat stream `exec:` (setara `adb install -r`).
- Package id `co.sat.hmdminstaller`, minSdk 28 (Android 9+), build debug.

## PENTING: belum pernah dikompilasi
Proyek ini ditulis tanpa akses Android SDK/Maven, jadi belum dikompilasi atau diuji di perangkat.
Kemungkinan perlu penyesuaian kecil, terutama di `AdbManager.kt` / `AdbController.kt`
(nama method libadb-android) dan versi `libadb-android` di `app/build.gradle.kts` (cek tag terbaru di JitPack).
Kirim pesan error build-nya jika ada, nanti diperbaiki.
