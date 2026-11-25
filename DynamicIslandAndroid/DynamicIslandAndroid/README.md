# Dynamic Island — Android

Port Android native (Kotlin) dari `dynamic_island_v9_optimized.py`. Pulau digambar sebagai overlay di atas layar.

## Build
1. Buka folder ini di **Android Studio** (Koala atau lebih baru), tunggu Gradle sync (wrapper dibuat otomatis).
2. Run ke HP (Android 8.0+ / API 26+), atau `Build > Build APK`.
3. Buka aplikasi → beri izin: *Tampil di atas aplikasi lain* dan *Akses notifikasi* → nyalakan switch.
   Di Android 13+ untuk APK hasil sideload, izin notifikasi perlu *Info aplikasi → ⋮ → Izinkan setelan terbatas* dulu.

## Pemetaan fitur Windows → Android
| Windows (PyQt6)                          | Android                                                        |
|------------------------------------------|----------------------------------------------------------------|
| Kapsul idle (jam, cuaca)                 | Idle: jam kiri, cuaca/baterai kanan (tengah kosong untuk kamera) |
| Spring grow/shrink, notch + telinga      | Sama (spring asimetris, mode notch opsional)                   |
| Musik (WinRT SMTC)                       | `MediaSessionManager` + listener notifikasi (Spotify, YT Music, dst.) |
| Player: cover, scrub, prev/play/next     | Sama, + tombol buka aplikasi musik                             |
| Lirik (lrclib)                           | Sama, tersinkron                                               |
| Notifikasi + balas cepat                 | `NotificationListenerService` + `RemoteInput` (WhatsApp, Telegram, dll.) |
| Pengisian daya                           | `ACTION_POWER_CONNECTED`                                       |
| HUD volume / kecerahan / Bluetooth       | `VOLUME_CHANGED_ACTION`, `ContentObserver`, `ACL_CONNECTED`    |
| Cuaca (Open-Meteo)                       | Sama                                                           |
| Pengaturan (skala, warna, auto-hide…)    | Layar pengaturan + live-apply                                  |
| Tray                                     | Notifikasi foreground service                                  |

## Tidak dibawa (tidak ada padanan di Android)
Quick Drop file, riwayat clipboard (Android 10+ memblokir baca clipboard di latar), screenshot snip, deteksi fullscreen/Focus Assist,
deteksi audio generik (pycaw), pemeriksa update GitHub. Tombol bulan di player = "Jangan ganggu" **khusus pulau** (menyembunyikan popup notifikasi pulau).

## Catatan
- Gerak: swipe ke atas = tutup, ketuk = buka player, tekan lama = pengaturan, ketuk di luar = tutup.
- Kode belum dikompilasi/diuji di perangkat (lingkungan pembuatan tanpa Android SDK).
