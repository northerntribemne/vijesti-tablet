package com.jovan.vijesti

import android.text.Html
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Zajedničke kategorije (svaki portal svoje rubrike zove drugačije).
 * Redoslijed je bitan: prva koja se poklopi pobjeđuje (npr. „Srbija + Hronika" → Hronika).
 */
enum class Kategorija(val ime: String, val korijeni: List<String>) {
    HRONIKA("Hronika", listOf("hronik")),
    SPORT("Sport", listOf("sport", "fudbal", "kosark", "košark", "tenis", "rukomet", "vaterpol", "odbojk", "atletik", "formul", "skijan")),
    POLITIKA("Politika", listOf("politik", "izbor")),
    EKONOMIJA("Ekonomija i turizam", listOf("ekonom", "turiz", "biznis", "novac", "finans")),
    STAV("Stavovi i kolumne", listOf("stav", "kolumn", "karikatur", "anket", "intervju", "rijec", "riječ", "komentar")),
    KULTURA("Kultura", listOf("kultur", "strip", "knjig", "pozori", "izlozb", "izložb", "umjetn")),
    ZABAVA("Zabava i magazin", listOf("zabav", "showbiz", "magazin", "razonod", "mozaik", "astro", "horoskop", "zdravlj",
        "bilje", "psiholog", "muzik", "film", "lifestyle", "recept", "tehnolog", "nauk")),
    SVIJET("Region i svijet", listOf("region", "balkan", "srbij", "svijet", "evrop", "rusij", "hrvatsk", "ukrajin", "bbc", "dw", "bih")),
    DRUSTVO("Društvo", listOf("drustv", "društv", "crna", "podgoric", "grad", "penzion", "servis", "religij", "pravoslav",
        "vjer", "obrazov", "zdravstv", "ekolog")),
    OSTALO("Ostalo", emptyList());

    companion object {
        /** [oznake] = nazivi rubrika i djelovi putanje (bez naslova članka). */
        fun razvrstaj(oznake: List<String>): Kategorija {
            val rijeci = oznake.flatMap { it.lowercase().split(Regex("[^\\p{L}\\p{N}]+")) }.filter { it.length >= 2 }
            for (k in values()) if (k.korijeni.any { kor -> rijeci.any { r -> if (kor.length <= 3) r == kor else r.startsWith(kor) } }) return k
            return OSTALO
        }

        /** Djelovi putanje linka, bez samog naslova (zadnji dio) i brojeva. */
        fun putanja(link: String): List<String> {
            val dijelovi = link.substringAfter("://").substringAfter('/').substringBefore('?')
                .split('/').filter { it.isNotEmpty() }
            return dijelovi.dropLast(1).filter { d -> d.length <= 30 && d.any { it.isLetter() } }
        }
    }
}

/** Portali sa kojih se vuku vijesti. */
enum class Izvor(val ime: String, val boja: Int, val adresa: String, val wp: Boolean = false) {
    VIJESTI("Vijesti", 0xFFE2231A.toInt(), "https://www.vijesti.me/rss"),
    CDM("CdM", 0xFF1E5AA8.toInt(),
        "https://www.cdm.me/wp-json/wp/v2/posts?per_page=30&_embed=wp:featuredmedia,wp:term" +
            "&_fields=date_gmt,title,link,excerpt,_links,_embedded", wp = true),
    DAN("Dan", 0xFF9B1B42.toInt(), "https://www.dan.co.me/feed/"),
    ANTENA("Antena M", 0xFFF08A00.toInt(), "https://rss.antenam.net/"),
    RTCG("RTCG", 0xFF0091D5.toInt(), "https://rtcg.me/vijesti/rss.html");
}

class Vijest(
    val izvor: Izvor,
    var naslov: String,
    val uvod: String,
    val link: String,
    var slika: String?,
    val vrijeme: Long,
    val rubrika: String,
) {
    var kategorija: Kategorija = Kategorija.OSTALO
    /** Kad je app prvi put vidio ovu vijest (0 = bila je tu od ranije). */
    var stigla: Long = 0L
    /** Ista vijest sa drugih portala (spojena u jednu karticu). */
    val takodje = ArrayList<Izvor>()
    val sviLinkovi = ArrayList<String>().apply { add(link) }

    fun uJson(): JSONObject = JSONObject().apply {
        put("i", izvor.name); put("n", naslov); put("u", uvod); put("l", link)
        put("s", slika ?: ""); put("v", vrijeme); put("r", rubrika); put("st", stigla); put("k", kategorija.name)
        put("t", JSONArray(takodje.map { it.name })); put("sl", JSONArray(sviLinkovi))
    }

    companion object {
        fun izJson(o: JSONObject): Vijest? = try {
            Vijest(Izvor.valueOf(o.getString("i")), o.getString("n"), o.optString("u"), o.getString("l"),
                o.optString("s").ifEmpty { null }, o.getLong("v"), o.optString("r")).apply {
                stigla = o.optLong("st")
                kategorija = try { Kategorija.valueOf(o.optString("k")) } catch (_: Exception) { Kategorija.OSTALO }
                o.optJSONArray("t")?.let { a -> for (k in 0 until a.length()) takodje.add(Izvor.valueOf(a.getString(k))) }
                o.optJSONArray("sl")?.let { a -> sviLinkovi.clear(); for (k in 0 until a.length()) sviLinkovi.add(a.getString(k)) }
            }
        } catch (_: Exception) { null }
    }
}

object Citac {

    fun preuzmi(izvor: Izvor): List<Vijest> {
        val tekst = Mreza.tekst(izvor.adresa)
        return if (izvor.wp) wordpress(izvor, tekst) else rss(izvor, tekst)
    }

    // ───────────── RSS ─────────────

    private fun rss(izvor: Izvor, xml: String): List<Vijest> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.trimStart().reader())
        val rez = ArrayList<Vijest>()
        var uItemu = false
        var naslov = ""; var link = ""; var opis = ""; var datum = ""
        val rubrike = ArrayList<String>()
        val oznake = ArrayList<String>()
        val slike = ArrayList<String>()
        var tag = ""
        while (true) {
            val e = try { p.next() } catch (_: Exception) { break }
            if (e == XmlPullParser.END_DOCUMENT) break
            when (e) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") {
                        uItemu = true; naslov = ""; link = ""; opis = ""; datum = ""; rubrike.clear(); oznake.clear(); slike.clear()
                    } else if (uItemu && tag == "category") {
                        p.getAttributeValue(null, "domain")?.let { oznake.addAll(Kategorija.putanja(it + "/x")) }
                        rubrike.add("")
                    } else if (uItemu && (tag == "enclosure" || tag == "media:content" || tag == "media:thumbnail")) {
                        val tip = p.getAttributeValue(null, "type") ?: p.getAttributeValue(null, "medium") ?: "image"
                        p.getAttributeValue(null, "url")?.let { if (tip.startsWith("image")) slike.add(it) }
                    }
                }
                XmlPullParser.TEXT -> if (uItemu) {
                    val t = p.text ?: ""
                    when (tag) {
                        "title" -> naslov += t
                        "link" -> link += t
                        "description" -> opis += t
                        "pubDate", "dc:date" -> datum += t
                        "category" -> if (rubrike.isNotEmpty()) rubrike[rubrike.size - 1] += t
                        "image:image" -> t.trim().let { if (it.isNotEmpty()) slike.add(it) }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item" && uItemu) {
                        uItemu = false
                        val vr = datum(datum.trim())
                        val l = link.trim()
                        if (vr > 0 && l.isNotEmpty() && naslov.isNotBlank()) {
                            val izOpisa = Regex("<img[^>]+src=\"([^\"]+)\"").find(opis)?.groupValues?.get(1)
                            val slika = (slike + listOfNotNull(izOpisa)).firstOrNull { valjanaSlika(it) }
                            val r = rubrike.map { cisto(it) }.filter { it.isNotEmpty() }
                            // za prikaz: prva rubrika koja nešto govori („Crna Gora"/„Vijesti" su preopšte)
                            val prikaz = r.firstOrNull { it.lowercase() !in setOf("crna gora", "vijesti", "naslovna") } ?: ""
                            rez.add(Vijest(izvor, cisto(naslov), cisto(opis), l, slika, vr, prikaz).apply {
                                kategorija = Kategorija.razvrstaj(r + oznake + Kategorija.putanja(l))
                            })
                        }
                    }
                    tag = ""
                }
            }
        }
        return rez
    }

    // ───────────── WordPress REST (CdM — RSS im je iza Cloudflare zaštite) ─────────────

    private fun wordpress(izvor: Izvor, json: String): List<Vijest> {
        val arr = JSONArray(json)
        val rez = ArrayList<Vijest>()
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val vr = try { f.parse(o.getString("date_gmt"))!!.time } catch (_: Exception) { continue }
            val emb = o.optJSONObject("_embedded")
            var slika: String? = null
            emb?.optJSONArray("wp:featuredmedia")?.optJSONObject(0)?.let { m ->
                val vel = m.optJSONObject("media_details")?.optJSONObject("sizes")
                slika = listOf("large", "medium_large", "full")
                    .firstNotNullOfOrNull { vel?.optJSONObject(it)?.optString("source_url")?.ifEmpty { null } }
                    ?: m.optString("source_url").ifEmpty { null }
            }
            val rubrike = ArrayList<String>()
            emb?.optJSONArray("wp:term")?.optJSONArray(0)?.let { a ->
                for (k in 0 until a.length()) a.optJSONObject(k)?.let { rubrike.add(cisto(it.optString("name"))) }
            }
            val rubrika = rubrike.firstOrNull() ?: ""
            rez.add(Vijest(izvor, cisto(o.optJSONObject("title")?.optString("rendered") ?: ""),
                cisto(o.optJSONObject("excerpt")?.optString("rendered") ?: ""),
                o.optString("link"), slika, vr, rubrika).apply {
                kategorija = Kategorija.razvrstaj(rubrike + Kategorija.putanja(link))
            })
        }
        return rez.filter { it.naslov.isNotBlank() && it.link.isNotEmpty() }
    }

    // ───────────── pomoćno ─────────────

    private val formati = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
        "dd.MM.yyyy'T'HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss",
    )

    fun datum(s: String): Long {
        if (s.isEmpty()) return 0L
        for (f in formati) {
            try { return SimpleDateFormat(f, Locale.US).parse(s)!!.time } catch (_: Exception) {}
        }
        return 0L
    }

    fun valjanaSlika(u: String): Boolean {
        val bez = u.substringBefore('?').lowercase()
        return u.startsWith("http") && (bez.endsWith(".jpg") || bez.endsWith(".jpeg") ||
            bez.endsWith(".png") || bez.endsWith(".webp"))
    }

    @Suppress("DEPRECATION")
    fun cisto(html: String): String {
        val bezSlika = html.replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), " ")
        return Html.fromHtml(bezSlika).toString()
            .replace('￼', ' ').replace(' ', ' ').replace('​', ' ')
            .replace(Regex("\\s+"), " ").trim()
            .removeSuffix("[…]").removeSuffix("[...]").trim()
    }

    /** Dopuni vijest sa same stranice: skraćen naslov („…") i slika koja fali. */
    fun dopuni(v: Vijest) {
        val krnj = v.naslov.endsWith("...") || v.naslov.endsWith("…")
        if (!krnj && v.slika != null) return
        try {
            val glava = Mreza.glavaStranice(v.link)
            fun meta(ime: String): String? =
                Regex("<meta[^>]+(?:property|name)=\"$ime\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                    .find(glava)?.groupValues?.get(1)
                    ?: Regex("<meta[^>]+content=\"([^\"]+)\"[^>]+(?:property|name)=\"$ime\"", RegexOption.IGNORE_CASE)
                        .find(glava)?.groupValues?.get(1)
            if (krnj) meta("og:title")?.let { t -> cisto(t).let { if (it.length > 10) v.naslov = it } }
            if (v.slika == null) ogSlika(glava)?.let { v.slika = it }
        } catch (e: Exception) {
            android.util.Log.w("Vijesti", "dopuna ${v.link}: ${e.message}")
        }
    }

    /** og:image iz zaglavlja stranice (samo ako liči na pravu sliku, ne „…/upload/"). */
    fun ogSlika(glava: String): String? {
        val m = Regex("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(glava)
            ?: Regex("<meta[^>]+content=\"([^\"]+)\"[^>]+property=\"og:image\"", RegexOption.IGNORE_CASE).find(glava)
        @Suppress("DEPRECATION")
        val u = m?.groupValues?.get(1)?.let { Html.fromHtml(it).toString() } ?: return null
        return u.takeIf { valjanaSlika(it) }
    }

    // ───────────── spajanje iste vijesti sa više portala ─────────────

    private fun rijeci(n: String): Set<String> =
        n.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.length > 3 }.toSet()

    private fun slicne(a: Set<String>, b: Set<String>): Boolean {
        if (a.size < 3 || b.size < 3) return false
        val zajed = a.intersect(b).size
        return zajed.toDouble() / minOf(a.size, b.size) >= 0.6
    }

    /** Sve vijesti iz svih portala → najnovijih [koliko], bez duplikata. */
    fun spoji(sve: List<Vijest>, koliko: Int): List<Vijest> {
        val sad = System.currentTimeMillis()
        val sortirane = sve.filter { it.vrijeme <= sad + 10 * 60_000L }.sortedByDescending { it.vrijeme }
        val rez = ArrayList<Vijest>(); val kljucevi = ArrayList<Set<String>>()
        for (v in sortirane) {
            val r = rijeci(v.naslov)
            val k = kljucevi.indexOfFirst { slicne(it, r) }
            if (k >= 0) {
                val g = rez[k]
                if (g.izvor != v.izvor && v.izvor !in g.takodje) g.takodje.add(v.izvor)
                g.sviLinkovi.add(v.link)
                if (g.slika == null && v.slika != null) g.slika = v.slika
            } else if (rez.size < koliko) {
                rez.add(v); kljucevi.add(r)
            }
        }
        return rez
    }
}
