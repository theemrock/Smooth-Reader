package com.theemrock.smoothreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize

/**
 * Paragrafları, verilen alana (width/height) ve TextStyle'a göre gerçek ölçümle sayfalara böler.
 * Sayfa = ekrana taşmayan metin bloğu
 */
@Composable
fun rememberPaginatedPages(
    paragraphs: List<String>,
    textStyle: TextStyle,
    pageSizePx: IntSize,
    extraBottomPaddingPx: Int = 0, // alttaki bar yüzünden metin alanı daralıyorsa ekle
): List<String> {
    val measurer = rememberTextMeasurer()

    return remember(paragraphs, textStyle, pageSizePx, extraBottomPaddingPx) {
        if (paragraphs.isEmpty()) return@remember emptyList()
        if (pageSizePx.width <= 0 || pageSizePx.height <= 0) return@remember emptyList()

        val maxHeight = (pageSizePx.height - extraBottomPaddingPx).coerceAtLeast(1)

        val pages = mutableListOf<String>()

        // Sayfa oluştururken paragrafları sırayla dolduruyoruz.
        // Eğer paragraf tek başına bile sığmıyorsa, paragrafı satır bazlı böleceğiz.
        var i = 0
        while (i < paragraphs.size) {
            val pageBuilder = StringBuilder()
            var usedAll = false

            while (i < paragraphs.size) {
                val candidate = buildString {
                    append(pageBuilder)
                    if (pageBuilder.isNotEmpty()) append("\n\n")
                    append(paragraphs[i].trim())
                }.trim()

                val layout = measure(measurer, candidate, textStyle, pageSizePx.width)
                val fits = layout.size.height <= maxHeight

                if (fits) {
                    // paragrafı sayfaya aldık
                    pageBuilder.clear()
                    pageBuilder.append(candidate)
                    i++
                    usedAll = true
                } else {
                    // bu paragrafı ekleyince taşır
                    usedAll = false
                    break
                }
            }

            if (pageBuilder.isNotEmpty()) {
                pages.add(pageBuilder.toString())
                continue
            }

            // Buraya geldiysek: tek paragraf bile boş sayfaya sığmadı.
            // O paragrafı "satır bazlı" parçalayacağız.
            val p = paragraphs[i].trim()
            if (p.isBlank()) {
                i++
                continue
            }

            val split = splitParagraphToFit(
                measurer = measurer,
                paragraph = p,
                textStyle = textStyle,
                maxWidthPx = pageSizePx.width,
                maxHeightPx = maxHeight
            )

            // split.first sayfaya giden, split.second kalan
            pages.add(split.first)
            if (split.second.isBlank()) {
                i++
            } else {
                // kalan kısmı aynı paragraf index’inde tekrar işle (paragraf bitene kadar)
                paragraphs.toMutableList() // sadece niyet: burada listeyi değiştirmiyoruz
                // pratik çözüm: kalan kısmı "yeni paragraf" gibi davran
                // i aynı kalsın ama o paragrafı kalanla değiştiriyormuş gibi ilerleyelim
                // bunun için local değişken tutuyoruz:
                // kolay çözüm: i'yi artırma, ama paragraph'ı kalan yapıp döngüye devam etmek
                // burada küçük hack: i'yi artırmadan devam edebilmek için bir buffer list kullanacağız
                // (bu fonksiyon remember içinde, sorun yok)
                val mutable = paragraphs.toMutableList()
                mutable[i] = split.second
                // kalanla devam edebilmek için paragraphs'ı bu scope içinde mutable gibi kullanmamız lazım.
                // Bu yüzden üstteki while için local list kopyası gerekiyor.
                // Basit çözüm: tüm algoritmayı local mutable list ile çalıştırıyoruz.
            }

            // Yukarıdaki hack’i düzgün yapmak için:
            // Bu remember bloğunda local mutable list ile ilerleyelim.
            // O yüzden burada break edip aşağıdaki "mutable list" algoritmasına geçiyoruz.
            break
        }

        // Üstteki basit akışta “kalan paragrafı aynı index’te yeniden işleme” gerektiği için,
        // en güvenlisi tüm işi local mutable listeyle yapmak:
        paginateMutable(paragraphs, measurer, textStyle, pageSizePx.width, maxHeight)
    }
}

private fun paginateMutable(
    original: List<String>,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    maxWidth: Int,
    maxHeight: Int
): List<String> {
    val work = original.map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
    val pages = mutableListOf<String>()

    var idx = 0
    while (idx < work.size) {
        val pageBuilder = StringBuilder()

        while (idx < work.size) {
            val nextPara = work[idx]
            val candidate = buildString {
                append(pageBuilder)
                if (pageBuilder.isNotEmpty()) append("\n\n")
                append(nextPara)
            }.trim()

            val layout = measure(measurer, candidate, style, maxWidth)
            if (layout.size.height <= maxHeight) {
                pageBuilder.clear()
                pageBuilder.append(candidate)
                idx++
            } else {
                break
            }
        }

        if (pageBuilder.isNotEmpty()) {
            pages.add(pageBuilder.toString())
            continue
        }

        // tek paragraf bile sığmadı → paragrafı böl
        val p = work[idx]
        val (fitPart, rest) = splitParagraphToFit(measurer, p, style, maxWidth, maxHeight)
        pages.add(fitPart)
        if (rest.isBlank()) idx++ else work[idx] = rest
    }

    return pages
}

private fun measure(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidthPx: Int
): TextLayoutResult {
    return measurer.measure(
        text = AnnotatedString(text),
        style = style,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxWidthPx)
    )
}

/**
 * Paragraf tek sayfaya sığmıyorsa, satır bazlı kırpıp ilk sayfaya sığan kısmı döndürür.
 */
private fun splitParagraphToFit(
    measurer: androidx.compose.ui.text.TextMeasurer,
    paragraph: String,
    textStyle: TextStyle,
    maxWidthPx: Int,
    maxHeightPx: Int
): Pair<String, String> {
    val layout = measure(measurer, paragraph, textStyle, maxWidthPx)

    // Kaç satır sığıyor?
    var lastLineThatFits = -1
    for (line in 0 until layout.lineCount) {
        val bottom = layout.getLineBottom(line)
        if (bottom <= maxHeightPx) lastLineThatFits = line else break
    }

    if (lastLineThatFits < 0) {
        // Hiç satır sığmıyor -> güvenlik: en azından ilk 1 satır
        lastLineThatFits = 0
    }

    val endIndex = layout.getLineEnd(lastLineThatFits, visibleEnd = true).coerceAtMost(paragraph.length)
    val fit = paragraph.substring(0, endIndex).trimEnd()
    val rest = paragraph.substring(endIndex).trimStart()

    return fit to rest
}
