# Smooth Reader - Codex Proje Talimatları

## 1. Projenin Amacı

Bu proje Android / Kotlin / Jetpack Compose tabanlı özel bir e-kitap okuyucu uygulamasıdır.

Amaç uzun metinleri telefonda rahat okumaya ve gerektiğinde dinlemeye odaklanan sade, hızlı ve güvenilir bir okuma deneyimi sunmaktır.

Uygulamanın iki temel Reader modu vardır:

- READ: Görsel okuma modu
- LISTEN: Android TextToSpeech tabanlı dinleme modu

READ ve LISTEN aynı kitabı ve aynı canonical kalıcı okuma konumunu paylaşmalıdır.

Mevcut temel formatlar:

- EPUB
- TXT
- DOCX

---

## 2. Temel Çalışma Yöntemi

Proje kontrollü ve aşamalı geliştirilecektir.

Her görev bağımsız, mümkün olduğunca küçük ve test edilebilir bir geliştirme dilimi olarak ele alınmalıdır.

Kurallar:

1. Yalnızca kullanıcının verdiği mevcut görevi ele al.
2. Kullanıcı istemeden sonraki göreve geçme.
3. Her görevde bütün projeyi baştan analiz etme.
4. Önce yalnızca görevle ilgili dosyaları ve zorunlu doğrudan bağımlılıklarını incele.
5. Görev kapsamı dışındaki sorunları kendiliğinden düzeltme.
6. Önemli kapsam dışı problem görülürse sonuç raporunda kısaca belirt.
7. Gereksiz refactor yapma.
8. En küçük güvenli ve geri alınabilir değişikliği tercih et.
9. Çalışan mevcut davranışları gereksiz yere değiştirme.
10. Kullanıcı istemedikçe yeni özellik ekleme.
11. Gereksiz dependency ekleme.
12. Gereksiz abstraction veya mimari katman oluşturma.
13. Bir problemi çözmek için tüm dosyayı yeniden yazma; mümkün olduğunca hedefli değişiklik yap.
14. Dosya, sınıf veya public API isimlerini yalnız gerçekten gerekliyse değiştir.
15. Ürün davranışında görev sonucunu etkileyen gerçek bir belirsizlik varsa tahmin etme; kullanıcıya sor.

Amaç projeyi her görevde yeniden incelemek değil, verilen problemi minimum kapsamla çözmektir.

---

## 3. Önceki Tamamlanmış Görevler

Tamamlanmış önceki görevleri her yeni görevde yeniden analiz etme veya doğrulama.

2A, 2B, 3A, 3B, 3C veya başka eski görevlerin geçmiş uygulama ayrıntılarını yalnızca mevcut görev doğrudan gerektiriyorsa incele.

Yeni görev sırasında:

- Yalnızca mevcut görevin etkilediği kodu incele.
- Önceki mimariyi yalnız mevcut değişiklik için gerekli olduğu ölçüde aç.
- Geçmiş görevler için ayrı doğrulama turu yapma.
- Geçmiş görevleri sonuç raporunda tek tek tekrar anlatma.
- Kullanıcı açıkça istemedikçe eski görevlerin manuel cihaz senaryolarını yeniden doğrulama.
- Mevcut otomatik testlerin geçmesi geçmiş davranışlar için temel regresyon kontrolüdür.

Bir promptta önceki bir sistemin korunması özellikle belirtilmişse yalnız o invariant açısından kontrol et.

Örneğin mevcut görev canonical ReadingPosition'ı etkiliyorsa canonical position korunmasını kontrol etmek gerekir.

Ancak bunun için bütün eski görevi yeniden analiz etmek gerekmez.

---

## 4. Görev Akışı

Kod değişikliği gereken görevlerde mümkün olduğunca şu sırayı izle:

1. Görevle ilgili mevcut kodu incele.
2. Gerçek nedeni belirle.
3. Minimum gerekli değişikliği belirle.
4. Gerekli kodu değiştir.
5. Görev için uygun doğrulamayı çalıştır.
6. Hata varsa mevcut görev kapsamında düzelt.
7. Kısa sonuç raporu ver.
8. DUR ve kullanıcının cihaz testi veya sonraki talimatını bekle.

Bir görevi tamamladıktan sonra kendiliğinden başka göreve geçme.

Gerçek bug/regresyon görevlerinde, çözüm açık değilse önce root cause bul.

Aynı probleme kanıtsız şekilde art arda farklı düzeltmeler uygulama.

---

## 5. Build ve Test Stratejisi

Her küçük değişiklikte otomatik olarak bütün projeyi tekrar tekrar build/test etme.

Doğrulama görev riskine göre yapılmalıdır.

### Küçük ve lokal değişiklik

Mümkünse yalnız ilgili hedefli testleri çalıştır.

Build gerekli değilse sırf rutin olduğu için assembleDebug çalıştırma.

### Orta veya davranışsal değişiklik

İlgili testleri çalıştır.

Compile/build riski varsa:

gradlew.bat assembleDebug

çalıştır.

### Büyük, Reader davranışını etkileyen veya riskli değişiklik

Gerekliyse:

gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug

çalıştır.

Kullanıcının görev promptunda belirli build/test komutları açıkça istenmişse prompttaki doğrulamayı uygula.

Aynı doğrulamayı gereksiz yere birden fazla kez çalıştırma.

Build başarısızsa ve hata yapılan değişiklikten kaynaklanıyorsa görevi tamamlanmış sayma.

Görevle ilgisiz eski bir build problemi görülürse bildir; kapsam dışı büyük düzeltme yapma.

Kod değiştirilmemiş salt analiz/teşhis görevlerinde kullanıcı istemedikçe build/test çalıştırma.

---

## 6. Görev Sonu Raporu

Uzun proje özeti yazma.

Varsayılan rapor kısa olmalıdır:

1. Root cause / bulunan neden
2. Değiştirilen dosyalar
3. Yapılan değişiklik
4. Çalıştırılan test/build ve sonucu
5. Gerçek cihazda kontrol edilmesi gereken kısa test listesi
6. Varsa dokunulmayan önemli kapsam dışı problem

Prompt özel bir rapor formatı istiyorsa onu kullan.

Geçmiş görevlerin durumunu yeniden anlatma.

Raporu verdikten sonra DUR.

---

## 7. UI Koruma Kuralı

Mevcut UI üzerinde uzun süredir çalışılmıştır.

Özellikle ReaderScreen ve alt kontrol alanı sebepsiz yere değiştirilmemelidir.

Backend, parser, progress, pagination veya navigation problemi çözülürken mevcut görsel tasarımı koru.

Açıkça gerekmedikçe:

- butonların yerini değiştirme,
- alt kontrol panelini yeniden tasarlama,
- progress göstergesini değiştirme,
- Reader metin alanının genel düzenini değiştirme,
- HomeScreen kitaplık düzenini değiştirme,
- renk veya tipografiyi yeniden tasarlama.

Teknik olarak zorunlu olmayan UI değişikliği yapma.

---

## 8. Canonical Okuma Konumu

Kalıcı okuma konumunun kaynağı pageIndex değildir.

Canonical model içerikteki gerçek konuma dayanmalıdır.

Mevcut temel model:

ReadingPosition(
    paragraphIndex,
    characterOffset
)

veya mevcut kodda bunun doğrudan eşdeğeridir.

pageIndex:

- ekran boyutuna,
- font boyutuna,
- font tipine,
- kullanılabilir text viewport'una,
- layout'a,
- pagination algoritmasına

bağlıdır ve persistent progress olarak kullanılmamalıdır.

Home, Resume, READ ve LISTEN aynı canonical reading position sistemini kullanmalıdır.

Drag veya geçici animation offset'i persistent progress değildir.

Yarım kalan veya iptal edilen görsel transition canonical position'ı değiştirmemelidir.

---

## 9. READ ve LISTEN

### READ

READ görsel okuma modudur.

Manuel navigation desteklenir.

Otomatik progression TTS değildir.

READ automatic progression görsel viewport ve canonical reading position ile kontrollü şekilde ilerlemelidir.

### LISTEN

LISTEN Android TextToSpeech kullanır.

TTS'nin gerçek source/range callback bilgisi okuma konumu ve active sentence için temel kaynaktır.

READ ve LISTEN aynı canonical reading position'ı paylaşır.

Bir modda ilerlenen gerçek konumdan diğer modda devam edilebilmelidir.

TTS callback, generation ve cancellation yarışlarına dikkat et.

---

## 10. Resume ve Navigation

Temel akış:

Home
→ Resume
→ READ veya LISTEN
→ Reader

Kullanıcı Reader'dan geri dönerek Resume üzerinden diğer moda geçebilmelidir.

Reader → Resume ile Reader → Home aynı navigation eylemi değildir.

Bu ayrımı koru.

---

## 11. Dosya Formatları

Şu anda desteklenen temel formatlar:

- EPUB
- TXT
- DOCX

Şimdilik desteklenmiş kabul edilmemesi gereken diğer formatlar:

- eski binary DOC
- ODT
- RTF
- tanımlanmamış diğer formatlar

Bir format enum/model içinde bulunuyor diye gerçekten destekleniyor varsayma.

Desteklenmeyen dosyayı sessizce TXT olarak yorumlama.

### Şimdilik kapsam dışı

Kullanıcı ayrıca görev vermedikçe aşağıdaki özellikleri kendiliğinden geliştirme:

- DOCX → EPUB
- text selection
- bookmark
- highlight/note sistemi
- selected-text actions
- dictionary/translation
- TTS pronunciation correction
- cloud TTS

Bunlar mevcut ilk sürümü tamamlamak için zorunlu değildir.

---

## 12. EPUB Yaklaşımı

EPUB için tam görsel EPUB renderer hedeflenmemektedir.

Öncelik:

- güvenilir metin çıkarımı,
- doğru spine/okuma sırası,
- kullanılabilir paragraf yapısı,
- Unicode/Türkçe doğruluğu,
- kontrollü hata davranışı,
- makul bellek kullanımıdır.

CSS, görseller ve özgün EPUB sayfa tasarımını Reader'da yeniden üretmek temel hedef değildir.

EPUB parser'a yalnız mevcut görev gerçekten EPUB parsing/loading ile ilgiliyse dokun.

Reader veya gesture görevi sırasında EPUB parser'ı yeniden inceleme.

---

## 13. Cache ve Veri Kaynağı

ParsedCache performans optimizasyonudur.

Kitabın tek kalıcı veri kaynağı değildir.

Android process öldüğünde memory cache kaybolabilir.

Kitap kalıcı URI/kitap kaydı üzerinden gerektiğinde yeniden yüklenebilmelidir.

Cache miss normal durumdur.

Cache miss nedeniyle kayıtlı canonical reading position'ı sıfırlama.

Cache/parser kodunu yalnız mevcut görev doğrudan gerektiriyorsa incele.

---

## 14. Parser ve Reader Ayrımı

Parser belgeyi ortak okunabilir içerik modeline dönüştürür.

Reader bu ortak modeli kullanıcıya sunar.

Format-specific ayrıntıları ReaderScreen içine taşıma.

ReaderScreen'in EPUB/DOCX/TXT iç yapısını bilmesi gerekmemelidir.

Yeni format desteğinde mümkün olduğunca ortak Reader modelini kullan.

---

## 15. ReaderScreen Özel Kuralı

ReaderScreen büyük ve hassas bir dosyadır.

Görev ReaderScreen değişikliği gerektirmiyorsa dokunma.

Gerektiriyorsa:

- yalnız ilgili bölümü incele,
- minimum değişiklik yap,
- çalışan UI davranışını koru,
- ilgisiz refactor yapma.

ReaderScreen'i parçalara ayırmak veya genel mimarisini yeniden düzenlemek ayrı görevdir.

Başka bir problemi çözerken fırsattan istifade refactor yapma.

---

## 16. Pagination ve Performans

Büyük kitap performansı önemlidir.

Mevcut Reader bounded/windowed pagination yaklaşımını kullanıyorsa kullanıcı açıkça istemedikçe bunu full-book pagination'a dönüştürme.

Özellikle kaçın:

- tüm kitabı her layout değişiminde yeniden paginate etmek,
- full-book continuous layout oluşturmak,
- her frame ağır text ölçümü yapmak,
- her frame persistent storage yazmak,
- her frame tüm kitap verisini kopyalamak,
- gesture delta başına coroutine/job oluşturmak.

Performans problemi gördüğünde önce nedenini belirle.

Ölçülmemiş büyük optimizasyon veya mimari değişiklik yapma.

---

## 17. Gerçek Cihaz Doğrulaması

Build ve unit test tek başına kullanıcı davranışının doğru olduğunu kanıtlamaz.

Reader davranışları mümkün olduğunca gerçek cihazda doğrulanmalıdır.

Görev sonunda yalnız mevcut değişiklikle ilgili kısa test listesi ver.

Örneğin görev gerektiriyorsa:

- kitabı aç,
- ilgili gesture'ı dene,
- READ/LISTEN başlat,
- geri dön,
- tekrar aç,
- layout/font değiştir,
- process death sonrası devam et.

Her görevde bu senaryoların tamamını tekrar isteme.

Yalnız değişikliğin gerçekten etkilediği senaryoları seç.

Kullanıcının gerçek cihaz sonucu, implementasyon raporuyla çelişirse cihaz sonucunu gerçek bug sinyali olarak kabul et.

Aynı çözümü savunmak yerine root cause'u yeniden incele.

---

## 18. Kapsam ve Regresyon Koruması

Her görevde bütün eski sistemleri yeniden doğrulamak yerine yalnız değişiklikten doğrudan etkilenebilecek invariant'ları koru.

Örnek:

Pagination değişikliği:
- canonical position
- current/adjacent page davranışı
- layout invalidation

TTS değişikliği:
- generation/cancellation
- canonical source position
- READ/LISTEN mode isolation

Parser değişikliği:
- content order
- cache behavior
- cancellation
- Unicode/text extraction

Navigation değişikliği:
- Home/Resume/Reader ayrımı
- current book identity
- canonical resume position

Bunlar örnektir.

Her görevde bütün listeyi kontrol etme.

Mevcut görevin dokunmadığı subsystem'leri sırf regresyon ihtimali teorik olarak var diye açıp analiz etme.

---

## 19. Superpowers Kullanımı

Projede Superpowers skill'leri kullanılabilir.

Ancak:

1. Kullanıcının açık görev talimatı
2. Bu AGENTS.md
3. İlgili skill workflow'u

sırasıyla görev kapsamını belirler.

Kullanıcı ayrıntılı bir prompt ile:

- amacı,
- kapsamı,
- dokunulmayacak alanları,
- hedef davranışı,
- doğrulamayı

yeterince tanımlamışsa aynı tasarımı yeniden brainstorm etme veya tekrar onay isteme.

Bu promptu onaylanmış çalışma planı olarak kabul et.

Uygun kullanım:

- bug/regresyon: systematic-debugging
- uygun yeni davranış: test-driven-development
- tamamlamadan önce: verification-before-completion
- özellikle gerekli/istenmişse: requesting-code-review

Brainstorming yalnız:

- gereksinim gerçekten belirsizse,
- birden fazla ciddi mimari yaklaşım arasında seçim gerekiyorsa,
- yeni subsystem tasarlanıyorsa,
- kullanıcı özellikle tasarım/brainstorming istiyorsa

kullanılmalıdır.

Superpowers:

- kapsamı genişletme,
- gereksiz refactor,
- yeni dependency,
- kendiliğinden yeni özellik,
- tamamlanmış görevleri yeniden analiz etme

izni değildir.

Skill mevcut görevden daha geniş çalışma öneriyorsa mevcut görev kapsamını koru.

---

## 20. En Önemli Kural

Bu projeyi her görevde yeniden "düzeltmeye" çalışma.

Bir seferde tek problem:

incele
→ root cause
→ minimum düzeltme
→ gerekli kadar test/build
→ kullanıcı cihazda doğrulasın
→ DUR

Tamamlanmış işleri yeniden açma.

Mevcut aşama cihazda doğrulanmadan sonraki büyük geliştirme aşamasına kendiliğinden geçme.
