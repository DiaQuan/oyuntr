# OyunTR — Bağımsız Temel

OyunTR, oyunları Türkçeye çevirmek ve yapay zekâ ile seslendirmek için geliştirilen özgün bir Android TV ürünüdür. Nino ve CouchSubs yalnızca araştırma kaynaklarıdır; kodları, varlıkları ve arayüzleri projeye alınmamıştır.

## Mevcut kilometre taşı

Android TV uygulama kabuğu ve kaynak düzeyinde PS4 bağlantı hattı bulunuyor: elle konsol eşleme ekranı, chiaki-ng genel C API'sine bağlı JNI oturumu, Android H.264 görüntüsü, kare yakalama, OCR, Türkçe çeviri, altyazı katmanı, cihaz TTS yedeği ve Chatterbox Multilingual V3 relay entegrasyonu. GitHub Actions'taki Kotlin, `protoc` ve Opus bulucu hataları giderildikten sonraki son koşum, chiaki-ng'in `opus/opus.h` başlığını bulamamasıyla durdu. OyunTR'ın Opus bulucusu şimdi Opus başlıklarını chiaki-ng'in beklediği `opus/` dizininde hazırlıyor; bu değişikliğin yeni koşumu bekleniyor. Şu an doğrulanmış APK veya PS4 bağlantısı bulunmuyor. Chatterbox için model sunucusu, HTTPS relay ve karakter başına izinli WAV referansı gerekir; servis henüz çalıştırılmadı.

[PS4 entegrasyon tasarımı](docs/ps4-remote-play.md), [lisans notu](docs/licensing.md) ve [dublaj tasarımı/sınırları](docs/dubbing.md).

## GitHub kaynak paketi

Depo Android Studio/Gradle kaynak düzenindedir. Derleme için Android SDK 35, NDK `28.2.13676358`, CMake 3.22.1 ve Java 17 gerekir. İlk native derleme, chiaki-ng ile sabitlenmiş üçüncü taraf bağımlılıklarını GitHub'dan indirir; internet bağlantısı gerekir. Bu bağımlılık grafiği henüz derlenerek doğrulanmadı. Ayrıntılar için [üçüncü taraf bildirim envanterine](THIRD_PARTY_NOTICES.md) bakın.

Projeye henüz bir `LICENSE` seçilmedi. Bu nedenle GitHub'a yüklenebilir bir kaynak paketi olsa da açık kaynak lisansı verilmiş veya ikili dağıtıma hazır değildir. Lisans ve chiaki-ng uyumluluğu netleşmeden APK yayımlamayın.

`.github/workflows/main.yml`, `main`/`master` dalına gönderimlerde ve pull request'lerde Java 17, Android SDK 35, NDK 28.2.13676358, CMake 3.22.1, Gradle 8.9 ve `protoc` ile debug APK derlemesini dener. Başarılı derlemenin APK'sını 14 gün süreyle GitHub Actions artifact'i olarak saklar. Son CI, chiaki-ng'in `opus/opus.h` include düzeni ile Opus kaynak başlıklarının uyuşmaması yüzünden başarısız oldu; özel bulucuya uyumlu başlık kopyalama adımı eklendi. Yeni koşum bekleniyor.

## Ürün yönü

- Kumandayla kullanılabilen Android TV arayüzü
- PS4 görüntüsünden OyunTR'nin OCR ve altyazı çeviri hattına geçiş
- Aktif PS4 oturumuna gönderilen kumanda girdisi ve Opus'tan Android ses çıkışına oyun sesi
- Türkçe çeviri, oyuna özgü terimler ve önbellekleme
- İsteğe bağlı cihaz TTS veya self-host Chatterbox AI dublajı
- Her ana karakter ve NPC profiline ayrı ses referansı ve ifade/vurgu seviyesi

## Durum

| Alan | Durum |
| --- | --- |
| Bağımsız mimari ve Nino/CouchSubs ayrımı | Belgeli; kaynak kodları/arayüzleri kopyalanmadı |
| Android TV uygulaması | Kotlin ve CMake yapılandırma aşamaları ilerledi; son CI Opus başlık include hatası verdi, başlık düzeni uyarlandı; yeni koşum bekliyor |
| PS4 eşleme ve oturum | Elle konsol adresi, PIN ve PSN Account ID ekranı ile JNI kaynakta bağlı; derlenmedi/denenmedi |
| PS4 görüntüsü | H.264 MediaCodec ve SurfaceView bağlantısı kaynakta var; derlenmedi/denenmedi |
| PS4 oyun sesi | chiaki-ng Opus çözücü → PCM → Android `AudioTrack` hattı kaynakta bağlı; derlenmedi/denenmedi |
| Kumanda | TV tuşları ve gamepad girdileri oturuma gönderiliyor; canlı konsolda denenmedi |
| OCR / Türkçe çeviri / altyazı | Kaynakta bağlı; çalıştırma doğrulaması yok |
| Karakter başına dublaj | Her profil için ayrı WAV ses referansı yükleme ve ses eşlemesi; ifade/vurgu seviyesi; cihazda denenmedi |
| AI TTS | Android HTTPS relay istemcisi, WAV örneği yükleme/oynatma, klon sesi kataloğu, self-host Chatterbox Multilingual V3 relay'i ve relay tarafında sınırlandırılmış tekrar repliği WAV önbelleği kaynakta eklendi; model/TV ile denenmedi |
| Chatterbox bakım durumu | Resmî repo Ağustos 2026 itibarıyla arşiv/bakımsız olduğunu bildiriyor; operatör model sunucusunu kendisi yönetmeli |
| PSN hesap kimliği | Elle giriliyor; sıradan kullanıcı için otomatik bulma/edinme akışı yok |
| Derleme / APK / PS4 bağlantısı | Son CI native C++ derlemesinde Opus başlık hatasıyla durdu; başlık düzeni düzeltildi ama yeniden derlenmedi. Doğrulanmış APK yok; PS4 bağlantısı denenmedi |

Önceki konuşmada belirtilen `/mnt/data/OyunTR-Independent-Foundation.zip` bu ortamda bulunamadı. Mevcut çalışma, o arşivin gerçek içeriğinden değil konuşmadaki açıklamasından hareketle sürdürülüyor. APK oluşturulmadı; PS4 oturumu, relay modeli ve Android TV ses çıkışı gerçek cihazda denenmedi.
