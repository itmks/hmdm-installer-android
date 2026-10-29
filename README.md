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

### Tab USB Debugging (alternatif tanpa Wi-Fi)
Di bagian atas aplikasi ada dua tab: **Wireless Debugging** (alur di atas) dan **USB Debugging**.
1. HP target: Opsi Developer › aktifkan **USB debugging**; mode USB jangan "hanya mengisi daya".
2. Sambungkan HP target ke HP teknisi dengan kabel OTG (atau kabel USB-C ke USB-C yang mendukung host mode).
3. Tab **USB Debugging** › **Cari Perangkat** (daftar terisi otomatis saat kabel dipasang) › **Connect USB**.
4. Izinkan akses USB di HP teknisi, lalu setujui dialog **"Izinkan debugging USB?"** di HP target (centang "Selalu izinkan").
5. APK, **Install Semua APK**, **Set Device Owner** dan **Jalankan Semua** dipakai bersama kedua tab
   (di tab USB, "Jalankan Semua" = Connect USB › Install › Set Device Owner).
Hanya satu koneksi aktif: menyambung lewat USB memutus Wireless, dan sebaliknya.

Syarat device owner: HP target baru / habis factory reset dan belum ada akun (Google dll).
Satu HP tidak bisa memasang dirinya sendiri sebagai device owner dengan cara ini kecuali IP diisi 127.0.0.1 dan
syarat di atas terpenuhi.

## Catatan teknis
- ADB memakai library [libadb-android](https://github.com/MuntashirAkon/libadb-android) (JitPack) + Conscrypt (TLS 1.3).
- Kunci/sertifikat ADB dibuat sekali (`AdbManager.kt`) dan disimpan; pairing terikat pada kunci ini.
- USB: protokol ADB (CNXN/AUTH/OPEN/WRTE/CLSE) diimplementasikan langsung di `UsbAdb.kt` di atas Android USB Host API;
  memakai kunci RSA yang sama dengan jalur Wireless.
- Install = `cmd package install -r -S <ukuran>` lewat stream `exec:` (setara `adb install -r`).
- Package id `co.sat.hmdminstaller`, minSdk 28 (Android 9+), build debug.

## PENTING: belum pernah dikompilasi
Proyek ini ditulis tanpa akses Android SDK/Maven, jadi belum dikompilasi atau diuji di perangkat.
Kemungkinan perlu penyesuaian kecil, terutama di `AdbManager.kt` / `AdbController.kt`
(nama method libadb-android) dan versi `libadb-android` di `app/build.gradle.kts` (cek tag terbaru di JitPack).
Kirim pesan error build-nya jika ada, nanti diperbaiki.
