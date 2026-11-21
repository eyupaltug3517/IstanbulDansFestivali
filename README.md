# Retro Salon (Android)

Eski oyun severler için sıfırdan Kotlin ile yazılmış bir NES emülatörü.

## Özellikler
- **CPU:** 6502 (resmi + gizli komutlar), nestest ve blargg testlerini geçiyor
- **PPU:** nokta (dot) bazlı grafik, sprite 0 hit, 8x16 sprite, kaydırma
- **APU:** 2 kare dalga, üçgen, gürültü ve DMC kanalları (44.1 kHz ses)
- **Mapper'lar:** 0 (NROM), 1 (MMC1), 2 (UxROM), 3 (CNROM), 4 (MMC3), 7 (AxROM), 11, 66
- **Dokunmatik kumanda:** 8 yönlü yön tuşu, A, B, Start, Select (çoklu dokunma, titreşim)
- **Kayıt:** 4 slot anlık kayıt (save state) + pilli kartuş kayıtları (ör. Zelda) otomatik
- **Kütüphane:** otomatik ekran görüntüsü kapaklar, arama, favoriler, oynama süresi, "kaldığın yerden devam"
- **Oyun içi:** turbo A/B, hızlı ileri sarma (2-4x), geri sarma (~12 sn), Game Genie ve RAM hile kodları
- **Görüntü:** keskin piksel, CRT tarama çizgileri veya yumuşak filtre; ayarlanabilir kumanda boyutu/saydamlığı
- .zip içinden ve çoklu dosya seçerek oyun ekleme, dosya yöneticisinden "Birlikte aç"
- Dikey ve yatay ekran, Bluetooth/USB gamepad (L1 geri sar, R1 hızlı ileri) ve klavye desteği

## Kurulum
1. `app-release.apk` dosyasını telefona indir.
2. Aç → "Bilinmeyen kaynaklardan yükleme" izni ver → Yükle.
3. Uygulamada **ROM Ekle** ile `.nes` dosyanı seç.

Her `push`ta GitHub Actions bir APK üretir (Actions → son çalıştırma → *nes-emulator-apk*).
CI'daki APK debug anahtarıyla imzalanır.

## Derleme
```bash
scripts/fetch-test-roms.sh            # test ROM'larını indir
./gradlew :app:testDebugUnitTest      # testler
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Kendi anahtarınla imzalamak için kök dizine `keystore.properties` koy
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Bu dosya ve anahtar git'e girmez.

> ROM'lar uygulamaya dahil değildir. Yalnızca sahip olduğun oyunların yedeklerini veya
> ücretsiz homebrew oyunları kullan.
