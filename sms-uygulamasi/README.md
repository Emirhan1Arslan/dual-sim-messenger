# Çift SIM Mesajlar

Çift SIM'li Android telefonlar için varsayılan SMS uygulaması. Konuşmalar SIM'e göre
ayrılır (SIM 1 mavi, SIM 2 yeşil), cevaplar konuşmanın geldiği SIM'den gider.

Kotlin + Jetpack Compose (Material 3) ile yazıldı; tek modül, ViewModel + StateFlow.
Android 10 ve üzeri gerekir.

## Özellikler

- **SIM'e göre ayrım:** "Tümü" ve her SIM için ayrı sekme, okunmamış sayılarıyla. SIM rozeti
  yalnızca "Tümü" sekmesinde ve aramada görünür.
- **SIM adı ve rengi:** Menü → Ayarlar'dan her SIM'e ad verilir ve renk seçilir.
- **MMS:** Konuşma ekranındaki **+** ile fotoğraf gönderilir; gelen fotoğraflar konuşmada
  görünür. MMS için ilgili SIM'in mobil verisi açık olmalıdır.
- **Yeni mesaj:** "Kime" alanına ad ya da numara yazdıkça rehber listesi daralır.
- **Arama:** Kişi adı, numara ve mesaj metninde arama.
- **Telefon etme:** Konuşma ekranındaki telefon simgesi numarayı telefon uygulamasında açar.
- **Engelleme:** Konuşma menüsünden ya da Ayarlar'dan numara engellenir. Liste telefonun
  ortak engelli listesidir; engellenen numaranın mesaj ve aramaları telefona ulaşmaz.
- **Mesaj işlemleri:** Mesaja uzun basınca kopyala, sil, gönderilemeyeni yeniden gönder.
- **Bildirimler:** Başlıkta kişi ve SIM adı; bildirimden yanıtlama ve okundu işaretleme.

Bu sürümde olmayanlar: grup MMS'i, video/ses gönderme, kelimeye göre spam filtresi,
teslim raporları.

## APK'yı indirme

1. GitHub'da deponun **Actions** sekmesini açın.
2. **SMS APK** workflow'unun son başarılı (yeşil) çalışmasına girin.
3. Sayfanın altındaki **Artifacts** bölümünden `cift-sim-mesajlar-debug` dosyasını indirin
   ve zip'i açın; içinden `app-debug.apk` çıkar.

Kendiniz derlemek isterseniz (JDK 17+ ve Android SDK gerekir):

```
cd sms-uygulamasi
./gradlew assembleDebug
```

APK `app/build/outputs/apk/debug/app-debug.apk` yoluna yazılır.

## Kurulum

1. `app-debug.apk` dosyasını telefona kopyalayıp açın. Android "bilinmeyen kaynaklardan
   yükleme" izni isterse dosyayı açtığınız uygulama (ör. Dosyalar) için izin verin.
   Bilgisayardan kurmak için: `adb install -r app-debug.apk`
2. Uygulamayı açın.

## Varsayılan SMS uygulaması yapma

1. Açılış ekranında **Varsayılan SMS uygulaması yap** butonuna dokunun, açılan pencerede
   **Çift SIM Mesajlar**'ı seçip onaylayın.
2. **İzinleri ver** butonuna dokunup SMS, telefon, rehber ve bildirim izinlerini verin.
   (Bazı cihazlarda izinler rolle birlikte kendiliğinden verilir ve bu adım atlanır.)

Pencere açılmazsa: **Ayarlar → Uygulamalar → Varsayılan uygulamalar → SMS uygulaması**
yolundan Çift SIM Mesajlar'ı seçin. Eski SMS uygulamanıza dönmek için de aynı ekranı
kullanın; mesajlarınız telefonun ortak SMS veritabanında durduğu için kaybolmaz.
