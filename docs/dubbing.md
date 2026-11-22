# Türkçe altyazı dublajı

`AndroidTurkishDubbing`, OCR ve çeviri hattından gelen altyazıları alır. Metnin 450 ms kararlı kalmasını bekler, aynı karakter/metin satırını tekrarlamaz ve yeni altyazı gelince eski AI ses isteğini iptal eder. Ana karakter ve NPC profilleri ayrı ses eşlemesi saklar. OCR metninde `Karakter: replik` etiketi görünürse konuşanı bulur; yoksa kullanıcı aktif profili seçer.

AI motoru, resmî deposunda Türkçeyi desteklediği belirtilen Chatterbox Multilingual V3'tür. Her profil için kullanıcı izinli bir Türkçe WAV referansı yükler; uygulama WAV'ı HTTPS üzerinden OyunTR relay'ine aktarır ve relay referansı sunucuda saklar. Relay, Chatterbox `audio_prompt_path` ile konuşmacı sesini klonlayıp WAV döndürür. `exaggeration` ayarı profil bazında ifade/vurgu şiddetini değiştirir; ayrı semantik “üzgün/öfkeli” kontrolü değildir. Model çıktısına gömülü PerTh watermark ekler.

Relay aynı oyundaki aynı normalize edilmiş tam replik için üretilen WAV'ı kalıcı ve boyutu sınırlı önbellekte tutar. Anahtar, referans WAV parmak izini ve ifade seviyesini de içerir; aynı sunucudaki tekrarlar yalnızca bu değerler de eşleşirse tekrar kullanılır. Noktalama, harf büyüklüğü ve boşluklar normalize edilir; kelimeler yaklaşık eşleştirilmez, çünkü farklı anlamdaki bir repliğe yanlış ses bağlama riski var. Oyuncular arası ortak kullanım aynı Chatterbox relay'ini ve aynı referans kaydını kullanmayı gerektirir.

Ses örnekleri uygulamanın bağlı olduğu relay sunucusuna gider. Yalnızca açıkça kullanım iznine sahip olduğun tek konuşmacılı kayıtları kullan. Android uygulama relay tokenini bellekte tutar; yeniden açınca tekrar girilmesi gerekir. Model ve relay kurulumu [sunucu belgesinde](../backend/ai-dubbing/README.md).

Yerel yedek Android `TextToSpeech` motorudur; Türkçe ses varlığı cihazın yüklediği pakete bağlıdır. Uygulamada önizleme ve TTS veri kurulum kısayolu bulunur. Android API'leri: [TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech), [UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener).

## Lisans ve bakım durumu

Resmî Chatterbox deposu MIT lisansı bildiriyor, Türkçe dilini listeliyor ve projenin Ağustos 2026 itibarıyla artık desteklenmediğini belirtiyor. MIT model kullanımını ticari olarak mümkün kılar; ses kaydına ilişkin izinleri kapsamaz. Model, relay ve Android kodu bu ortamda çalıştırılarak denenmedi.

## Doğrulama sınırı

Kaynakta Android WAV yükleme, relay katalog çağrısı, referans sesle Chatterbox V3 çağrısı, WAV playback yolu ve relay tarafında diske yazılan sınırlı TTS önbelleği bulunuyor. Gradle/Kotlin/Android SDK araçları olmadığı için uygulama derlenmedi. Bu ortamda Python relay başlatılamadı; model indirme/çalıştırma, ses kalitesi, ifade ayarı, upload, önbellekli tekrar ve Android playback doğrulanmadı. PS4 oyun sesi ayrı Opus → PCM → Android `AudioTrack` yoludur; bu yol da cihazda test edilmedi.
