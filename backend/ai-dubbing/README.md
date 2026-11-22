# OyunTR Chatterbox Multilingual V3 relay

Bu servis Chatterbox Multilingual V3 modelini kendi sunucunda çalıştırır. Türkçe konuşmayı, her karakter için uygulamadan yüklenen referans WAV dosyasındaki ses özelliklerine yaklaştırır. Klonlanacak kayıtta konuşmacının açık izni bulunmalı; ses dosyaları bu relay sunucusuna gönderilir ve `CHATTERBOX_VOICES_DIR` altında saklanır.

## Kurulum

Resmî Chatterbox deposu Python 3.11 üzerinde Debian 11 ile test edilmiş ve `pip install chatterbox-tts` kurulumunu belgeliyor. CUDA GPU yoksa servis CPU'ya düşer; performans cihazın gücüne bağlıdır. İlk çalıştırma V3 ağırlıklarını Hugging Face'ten indirir.

```bash
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install chatterbox-tts
```

En az 32 karakterlik rastgele OyunTR relay tokeni belirle:

```bash
export OYUNTR_APP_TOKEN='uzun-rastgele-bir-token-degeri'
export OYUNTR_BIND='127.0.0.1'
export CHATTERBOX_VOICES_DIR='/srv/oyuntr/voice-references'
export CHATTERBOX_CACHE_DIR='/srv/oyuntr/speech-cache'
export CHATTERBOX_CACHE_MAX_GB='4'
python server.py
```

Servisi internete doğrudan açma; önüne HTTPS ters proxy koy. Uygulamada HTTPS adresini ve aynı OyunTR tokenini kaydet.

## Karakter sesi

Uygulamada aktif ana karakter veya NPC profilinde **WAV ses örneği yükle** seç. Kaydın 0,5–30 saniye, 16 MB altında, mono/stereo WAV ve tek Türkçe konuşmacı olması gerekir. Model resmî örnek kullanımındaki `audio_prompt_path` ile bu sesi referans alır. Kısa, temiz ve hedeflenen aynı dilde kayıt kullan. Yükleme sunucuya kalıcı WAV dosyası koyar; profil ekranındaki silme düğmesi bu karakterin kendi yüklediği örneği kaldırır. Oyun içi çeviri metinleri `POST /v1/speech` ile işlenir; loglarda içerik ve token yazılmaz.

Modelin `exaggeration` değeri karakter bazında 0,25–1,0 arasında ayarlanır. Bu ifade/vurgu miktarıdır; “üzgün/öfkeli” gibi ayrı duygu sınıfı komutu değildir. Her üretilen ses Chatterbox'ın gömülü PerTh işaretini taşır.

## Tekrar kullanılan replikler

Relay, üretilen WAV'ı diskte kalıcı olarak önbelleğe alır. Önbellek anahtarı oyun kimliği, noktalama/büyük-küçük harf/boşlukları standartlaştırılmış tam replik, yüklenen WAV referansının SHA-256 parmak izi, ifade seviyesi ve model/sürüm şemasını içerir. Aynı relay'e bağlı oyuncular aynı izinli referans WAV'ı ve aynı oyun/repliği kullanıyorsa ilk üretimden sonraki istekler aynı sesi tekrar kullanır; HTTP yanıtında `X-OyunTR-TTS-Cache: HIT` görünür. Farklı referans WAV'ı farklı bir ses kimliği sayılır.

Kelime benzerliğiyle yaklaşık eşleştirme yapılmaz: tek bir olumsuzluk veya isim farkı bile yanlış repliğin çalmasına neden olabilir. Varsayılan depolama `speech_cache`, üst sınır `CHATTERBOX_CACHE_MAX_GB` (varsayılan 4 GB); yer dolunca en eski erişilen klipler silinir. Önbellek yalnızca aynı relay sunucusunu kullanan istemciler arasında ortaktır; her kullanıcının ayrı kurduğu relay'ler birbirlerinin kliplerini paylaşmaz.

## Lisans ve bakım

- Resmî Chatterbox deposu kod/modeli MIT lisanslı yayımlıyor; ticari kullanıma izin veren lisans bu model seçiminin nedenidir. Dağıtılan kopyada lisans/copyright bildirimlerini koru.
- Resmî depo Ağustos 2026 itibarıyla projenin artık bakılmadığını bildiriyor. Güvenlik güncellemeleri ve upstream desteği beklenmemeli; sürümleri test edip kendi sunucunda sabitle.
- MIT lisansı, yüklenen kişilerin sesini kullanma izni vermez. Yalnızca açıkça kullanım hakkına sahip olduğun kayıtları yükle.
- Bu kaynakta OyunTR uyumlu relay var; model servisi, Android derlemesi, örnek ses üretimi ve PS4 akışı henüz cihazda doğrulanmadı.
