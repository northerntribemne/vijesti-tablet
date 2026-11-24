package com.jovan.vijesti

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Izvlači čist tekst članka sa stranice portala (bez reklama, dijeljenja, „pročitajte još"…). */
object Clanak {

    sealed class Blok {
        class Pasus(val tekst: CharSequence) : Blok()
        class Podnaslov(val tekst: String) : Blok()
        class Citat(val tekst: CharSequence) : Blok()
        class Stavka(val tekst: CharSequence) : Blok()
        class Slika(val url: String, val opis: String?) : Blok()
    }

    class Sadrzaj(val uvod: String?, val blokovi: List<Blok>)

    private val kes = object : LinkedHashMap<String, Sadrzaj>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sadrzaj>?) = size > 12
    }

    fun izKesa(link: String): Sadrzaj? = synchronized(kes) { kes[link] }

    fun preuzmi(v: Vijest): Sadrzaj {
        izKesa(v.link)?.let { return it }
        val s = when (v.izvor) {
            Izvor.CDM -> cdm(v)
            else -> {
                val doc = Jsoup.parse(Mreza.tekst(v.link), v.link)
                val okvir = when (v.izvor) {
                    Izvor.VIJESTI -> doc.selectFirst("[itemprop=articleBody]")
                    Izvor.DAN -> doc.selectFirst(".se-article--text")
                    Izvor.ANTENA -> doc.selectFirst("#article-body")
                    Izvor.RTCG -> doc.selectFirst(".storyFull")
                    else -> null
                } ?: najgusci(doc.body())
                val uvod = when (v.izvor) {
                    Izvor.RTCG -> okvir.selectFirst("p.lead")?.text()
                    Izvor.DAN -> doc.selectFirst(".se-article--text")?.previousElementSibling()
                        ?.takeIf { it.tagName() == "h2" }?.text()
                    else -> null
                }
                rasclani(okvir, uvod?.takeIf { it.length > 20 } ?: v.uvod.takeIf { it.isNotBlank() }, v)
            }
        }
        if (s.blokovi.isEmpty()) throw RuntimeException("tekst članka nije pronađen")
        synchronized(kes) { kes[v.link] = s }
        return s
    }

    /** CdM: WordPress daje sadržaj članka direktno. */
    private fun cdm(v: Vijest): Sadrzaj {
        val slug = v.link.trimEnd('/').substringAfterLast('/')
        val a = JSONArray(Mreza.tekst("https://www.cdm.me/wp-json/wp/v2/posts?slug=$slug&_fields=content"))
        val html = a.getJSONObject(0).getJSONObject("content").getString("rendered")
        val doc = Jsoup.parseBodyFragment(html, v.link)
        return rasclani(doc.body(), v.uvod.takeIf { it.isNotBlank() }, v)
    }

    /** Rezerva za nepoznat raspored: element sa najviše teksta u direktnim pasusima. */
    private fun najgusci(body: Element): Element {
        var naj = body; var max = 0
        for (e in body.select("div, article, section, main")) {
            val n = e.children().filter { it.tagName() == "p" }.sumOf { it.text().length }
            if (n > max) { max = n; naj = e }
        }
        return naj
    }

    private val SMECE = listOf(
        "script", "style", "noscript", "iframe", "form", "button", "svg", "video", "audio", "h1",
        ".ads", ".se-ad", "[class*=advert]", "[id^=protag]", "[id^=div-article-body]", ".follow-us-section",
        ".authorRow", ".artTime", ".author-source", ".dateTimeIconComment", ".lead", "[class*=share]",
        "[class*=related]", "[class*=social]", "[class*=newsletter]", ".VME_VIDEO", "[class*=comment]"
    )

    private fun rasclani(okvir: Element, uvod: String?, v: Vijest): Sadrzaj {
        val o = okvir.clone()
        for (s in SMECE) o.select(s).remove()
        val blokovi = ArrayList<Blok>()
        val vidjeneSlike = HashSet<String>()
        v.slika?.let { vidjeneSlike.add(bezVelicine(it)) }
        var kraj = false

        fun dodajSliku(img: Element, opis: String?) {
            val url = listOf("data-src", "data-lazy-src", "data-original", "src")
                .map { img.absUrl(it) }.firstOrNull { it.startsWith("http") } ?: return
            val sir = img.attr("width").toIntOrNull()
            if (sir != null && sir < 250) return
            if (!Citac.valjanaSlika(url)) return
            if (!vidjeneSlike.add(bezVelicine(url))) return
            blokovi.add(Blok.Slika(url, opis?.takeIf { it.isNotBlank() }))
        }

        fun tekstBlok(e: Element, tip: String) {
            for (img in e.select("img")) dodajSliku(img, null)
            val t = linija(e)
            val cist = t.toString().trim()
            if (cist.isEmpty()) return
            val malo = cist.lowercase()
            if (malo.startsWith("pogledajte još") || malo.startsWith("pogledajte video")) { kraj = true; return }
            if (malo.startsWith("pročitajte još") || malo.startsWith("pročitajte i") || malo.startsWith("preporučujemo")) return
            if (uvod != null && blokovi.isEmpty() && slicno(cist, uvod)) return
            val samoJako = e.select("strong, b").text().length >= cist.length - 2
            when {
                tip == "h" || (tip == "p" && samoJako && cist.length < 140) -> blokovi.add(Blok.Podnaslov(cist))
                tip == "q" -> blokovi.add(Blok.Citat(t))
                tip == "li" -> blokovi.add(Blok.Stavka(t))
                else -> blokovi.add(Blok.Pasus(t))
            }
        }

        fun prodji(el: Element) {
            for (c in el.children()) {
                if (kraj) return
                when (c.tagName()) {
                    "p" -> tekstBlok(c, "p")
                    "h2", "h3", "h4", "h5", "h6" -> tekstBlok(c, "h")
                    "blockquote" -> {
                        // ugrađeni tvitovi/instagram — samo tekst
                        if (c.select("p").size > 1 && c.className().isEmpty()) prodji(c) else tekstBlok(c, "q")
                    }
                    "li" -> tekstBlok(c, "li")
                    "img" -> dodajSliku(c, c.attr("alt").takeIf { it.length > 15 })
                    "figure" -> {
                        val opis = c.selectFirst("figcaption")?.text()
                        c.select("img").forEach { dodajSliku(it, opis) }
                    }
                    "table" -> {}
                    else -> {
                        val blokovska = c.children().any { it.tagName() in BLOKOVSKI }
                        if (blokovska) prodji(c)
                        else if (c.text().length > 60 && c.select("a").text().length < c.text().length / 2) tekstBlok(c, "p")
                        else c.select("img").forEach { dodajSliku(it, null) }
                    }
                }
            }
        }
        prodji(o)
        // na kraju često ostanu potpisi tipa „(Foto: …)" ili „Izvor: …"
        while (blokovi.isNotEmpty()) {
            val z = blokovi.last()
            if (z is Blok.Podnaslov) blokovi.removeAt(blokovi.size - 1) else break
        }
        return Sadrzaj(uvod, blokovi)
    }

    private val BLOKOVSKI = setOf("p", "div", "section", "article", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "ul", "ol", "li", "figure", "img", "table", "span")

    /** Tekst elementa sa sačuvanim podebljanjem i kurzivom, sažetim razmacima. */
    private fun linija(e: Element): CharSequence {
        val sb = SpannableStringBuilder()
        fun idi(n: Node, jako: Boolean, kos: Boolean) {
            when (n) {
                is TextNode -> {
                    var s = n.wholeText.replace(Regex("[\\s\\u00A0]+"), " ")
                    if (sb.isEmpty() || sb[sb.length - 1] == ' ' || sb[sb.length - 1] == '\n') s = s.trimStart()
                    if (s.isEmpty()) return
                    val p = sb.length
                    sb.append(s)
                    val stil = when { jako && kos -> Typeface.BOLD_ITALIC; jako -> Typeface.BOLD; kos -> Typeface.ITALIC; else -> -1 }
                    if (stil >= 0) sb.setSpan(StyleSpan(stil), p, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Element -> {
                    val t = n.tagName()
                    if (t == "br") {
                        while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.delete(sb.length - 1, sb.length)
                        if (sb.isNotEmpty()) sb.append('\n')
                        return
                    }
                    if (t == "img" || t == "script" || t == "style") return
                    val j = jako || t == "strong" || t == "b"
                    val k = kos || t == "em" || t == "i"
                    for (c in n.childNodes()) idi(c, j, k)
                }
            }
        }
        for (c in e.childNodes()) idi(c, false, false)
        while (sb.isNotEmpty() && sb[sb.length - 1].isWhitespace()) sb.delete(sb.length - 1, sb.length)
        while (sb.isNotEmpty() && sb[0].isWhitespace()) sb.delete(0, 1)
        return sb
    }

    private fun bezVelicine(u: String) =
        u.substringBefore('?').substringAfterLast('/').replace(Regex("[-_]\\d{2,4}x\\d{2,4}"), "").lowercase()

    private fun slicno(a: String, b: String): Boolean {
        val x = a.lowercase().take(60); val y = b.lowercase().take(60)
        return x.isNotEmpty() && (x.startsWith(y.take(40)) || y.startsWith(x.take(40)))
    }
}
