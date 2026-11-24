package com.jovan.vijesti

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Magazin prikaz: jedna vijest preko cijelog ekrana, slika + naslov, smjenjuju se same. */
class Prikaz(ctx: Context) : View(ctx) {

    // ───────── stanje ─────────
    var lista: List<Vijest> = emptyList(); private set
    private var indeks = 0
    private var prikazanaOd = System.currentTimeMillis()
    private var prethodna: Vijest? = null
    private var prethodnaOd = 0L
    private var prelazOd = 0L
    var trajanje = 15_000L
    var osvjezeno = 0L
    var greska = false
    var radi = false          // upravo preuzima
    private var banerOd = 0L
    private var banerTekst = ""

    // ───────── slike ─────────
    private val dir = File(ctx.cacheDir, "slike").apply { mkdirs() }
    private val kes = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 5
    }
    private val ucitano = HashMap<String, Long>()
    private val uToku = HashSet<String>()
    private val neuspjele = HashSet<String>()
    private val radnik = Executors.newSingleThreadExecutor()
    private val glavna = Handler(Looper.getMainLooper())

    // ───────── boje i četke ─────────
    private val CRVENA = 0xFFFF3B30.toInt()
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val lagan = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val obican = Typeface.create("sans-serif", Typeface.NORMAL)
    private val jak = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val tanak = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val mat = Matrix()
    private val rr = RectF()

    private class Rasporedi(val w: Int, val naslov: StaticLayout, val uvod: StaticLayout?)
    private val rasporedi = HashMap<Vijest, Rasporedi>()

    private val daniPuni = arrayOf("neđelja", "poneđeljak", "utorak", "srijeda", "četvrtak", "petak", "subota")
    private val mjeseci = arrayOf("januar", "februar", "mart", "april", "maj", "jun", "jul",
        "avgust", "septembar", "oktobar", "novembar", "decembar")

    // ───────────────────────── upravljanje ─────────────────────────

    fun postavi(nova: List<Vijest>, nove: List<Vijest>) {
        val tekuca = lista.getOrNull(indeks)?.link
        lista = nova
        neuspjele.clear()
        rasporedi.keys.retainAll(nova.toSet())
        if (nova.isEmpty()) { indeks = 0; invalidate(); return }
        val najnovija = nove.maxByOrNull { it.vrijeme }
        if (najnovija != null && nova.contains(najnovija)) {
            banerTekst = "NOVA VIJEST  ·  ${najnovija.izvor.ime}  ·  ${satMin(najnovija.vrijeme)}" +
                if (nove.size > 1) "   (+${nove.size - 1})" else ""
            banerOd = System.currentTimeMillis()
            idi(nova.indexOf(najnovija))
        } else {
            val k = nova.indexOfFirst { it.link == tekuca }
            if (k >= 0) indeks = k else idi(0)
        }
        val zadrzi = nova.mapNotNull { it.slika }.map { hes(it) }.toSet()
        radnik.execute {
            dir.listFiles()?.forEach { if (it.name !in zadrzi && System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() }
        }
        invalidate()
    }

    fun naprijed() { if (lista.isNotEmpty()) idi((indeks + 1) % lista.size) }
    fun nazad() { if (lista.isNotEmpty()) idi((indeks - 1 + lista.size) % lista.size) }

    private fun idi(i: Int) {
        val sad = System.currentTimeMillis()
        val cur = lista.getOrNull(indeks)
        if (cur != null && i != indeks) {
            prethodna = cur; prethodnaOd = prikazanaOd; prelazOd = sad
        }
        indeks = i.coerceIn(0, max(0, lista.size - 1))
        prikazanaOd = sad
        invalidate()
    }

    // ───────────────────────── crtanje ─────────────────────────

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val u = min(w, h) / 800f
        val sad = System.currentTimeMillis()

        if (lista.isEmpty()) {
            prazno(c, w, h, u, sad)
        } else {
            if (indeks >= lista.size) indeks = 0
            if (sad - prikazanaOd >= trajanje) naprijed()
            val cur = lista[indeks]

            fp.shader = null; fp.color = 0xFF101114.toInt(); c.drawRect(0f, 0f, w, h, fp)
            val p = ((sad - prelazOd) / PRELAZ).coerceIn(0f, 1f)
            val pr = prethodna
            if (pr != null && p < 1f) {
                // slike se pretapaju; stari tekst izblijedi, pa novi uplovi odozdo
                slikaSloj(c, pr, prethodnaOd, w, h, u, sad, 1f)
                slikaSloj(c, cur, prikazanaOd, w, h, u, sad, glatko(p))
                zatamnjenja(c, w, h)
                val izlaz = 1f - glatko((p / 0.4f).coerceIn(0f, 1f))
                if (izlaz > 0f) tekstSloj(c, pr, w, h, u, sad, izlaz, 0f)
                val ulaz = glatko(((p - 0.35f) / 0.65f).coerceIn(0f, 1f))
                if (ulaz > 0f) tekstSloj(c, cur, w, h, u, sad, ulaz, (1f - ulaz) * 26f * u)
            } else {
                prethodna = null
                slikaSloj(c, cur, prikazanaOd, w, h, u, sad, 1f)
                zatamnjenja(c, w, h)
                tekstSloj(c, cur, w, h, u, sad, 1f, 0f)
            }
            segmenti(c, w, h, u, sad)

            // sljedeću sliku unaprijed učitaj i pošalji u grafiku (nevidljivo, 1 piksel),
            // da prelaz ne zastane kad se ona prvi put crta
            slika(lista[(indeks + 1) % lista.size])?.let { nb ->
                bp.alpha = 1; rr.set(0f, 0f, 1f, 1f); c.drawBitmap(nb, null, rr, bp); bp.alpha = 255
            }
        }
        okvir(c, w, h, u, sad)
        baner(c, w, h, u, sad)
        postInvalidateDelayed(33)
    }

    private val PRELAZ = 1600f
    private fun glatko(t: Float) = t * t * (3 - 2 * t)

    /** boja sa pomnoženom prozirnošću */
    private fun al(boja: Int, a: Float): Int = (((boja ushr 24) * a).toInt() shl 24) or (boja and 0xFFFFFF)

    /** Slika vijesti, polagano zumiranje i klizanje (Ken Burns). */
    private fun slikaSloj(c: Canvas, v: Vijest, t0: Long, w: Float, h: Float, u: Float, sad: Long, a: Float) {
        if (a <= 0f) return
        val bmp = slika(v)
        val k = ((sad - t0).toFloat() / (trajanje + PRELAZ)).coerceIn(0f, 1f)
        val smjer = (v.link.hashCode() and 3)
        if (bmp != null) {
            val s0 = max(w / bmp.width, h / bmp.height)
            val s = s0 * (1.04f + 0.08f * k)
            val ox = bmp.width * s - w; val oy = bmp.height * s - h
            val fx = if (smjer and 1 == 0) 0.30f + 0.40f * k else 0.70f - 0.40f * k
            val fy = if (smjer and 2 == 0) 0.40f + 0.20f * k else 0.60f - 0.20f * k
            mat.reset(); mat.setScale(s, s); mat.postTranslate(-ox * fx, -oy * fy)
            val pojava = glatko(((sad - (ucitano[v.slika] ?: 0L)) / 900f).coerceIn(0f, 1f))
            bp.alpha = (a * pojava * 255).toInt()
            c.drawBitmap(bmp, mat, bp)
            bp.alpha = 255
        } else {
            // nema slike: platno u boji portala sa velikim imenom kao vodenim žigom
            fp.color = al(0xFFFFFFFF.toInt(), a)
            fp.shader = LinearGradient(0f, 0f, w, h, tamnije(v.izvor.boja, 0.55f), 0xFF0B0C10.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fp); fp.shader = null
            tp.reset(); tp.isAntiAlias = true; tp.typeface = serif; tp.textSize = 260f * u
            tp.color = al(0x14FFFFFF, a); tp.textAlign = Paint.Align.RIGHT
            c.drawText(v.izvor.ime, w - 40f * u + 30f * u * (1 - k), h * 0.48f, tp)
            tp.textAlign = Paint.Align.LEFT
        }
    }

    /** Zatamnjenja za čitljivost — crtaju se jednom, iznad obje slike. */
    private fun zatamnjenja(c: Canvas, w: Float, h: Float) {
        fp.shader = null
        fp.color = 0x2E000000; c.drawRect(0f, 0f, w, h, fp)   // blago zatamni cijelu sliku (svijetle fotografije)
        fp.color = 0xFFFFFFFF.toInt()
        fp.shader = LinearGradient(0f, 0f, 0f, h * 0.36f, 0xB8000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * 0.36f, fp)
        fp.shader = LinearGradient(0f, h * 0.22f, 0f, h,
            intArrayOf(0x00000000, 0x80000000.toInt(), 0xDC000000.toInt(), 0xF4000000.toInt()),
            floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.22f, w, h, fp)
        fp.shader = LinearGradient(0f, 0f, w * 0.75f, 0f, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w * 0.75f, h, fp)
        fp.shader = null
    }

    /** Portal, rubrika, vrijeme, naslov i uvod; [a] = prozirnost, [dy0] = pomak nadolje pri ulasku. */
    private fun tekstSloj(c: Canvas, v: Vijest, w: Float, h: Float, u: Float, sad: Long, a: Float, dy0: Float) {
        val (dx, dy) = pomak(u, sad)
        c.save(); c.translate(dx, dy)
        val L = 64f * u

        // ── zaglavlje: portal + rubrika ──
        tp.reset(); tp.isAntiAlias = true; tp.typeface = jak; tp.textSize = 25f * u
        tp.letterSpacing = 0.04f
        val ime = v.izvor.ime.uppercase()
        val iw = tp.measureText(ime)
        val top = 46f * u
        rr.set(L, top, L + iw + 34f * u, top + 44f * u)
        fp.color = al(v.izvor.boja, a); c.drawRoundRect(rr, 8f * u, 8f * u, fp)
        tp.color = al(0xFFFFFFFF.toInt(), a)
        c.drawText(ime, L + 17f * u, top + 31f * u, tp)
        val x0 = rr.right + 18f * u
        if (v.rubrika.isNotEmpty()) {
            tp.typeface = obican; tp.textSize = 21f * u; tp.letterSpacing = 0.16f; tp.color = al(0xE6FFFFFF.toInt(), a)
            tp.setShadowLayer(6f * u, 0f, 1f * u, al(0x99000000.toInt(), a))
            c.drawText(v.rubrika.uppercase(), x0, top + 30f * u, tp)
        }
        if (v.takodje.isNotEmpty()) {
            tp.typeface = obican; tp.textSize = 19f * u; tp.letterSpacing = 0.02f; tp.color = al(0xB3FFFFFF.toInt(), a)
            tp.setShadowLayer(6f * u, 0f, 1f * u, al(0x99000000.toInt(), a))
            c.drawText("piše i: " + v.takodje.joinToString(" · ") { it.ime }, L, top + 78f * u, tp)
        }
        tp.clearShadowLayer()

        // ── naslov i uvod (odozdo nagore) ──
        c.translate(0f, dy0)
        val sirina = (w * 0.74f).toInt()
        val r = raspored(v, sirina, u)
        var y = h - 74f * u
        r.uvod?.let { ul ->
            y -= ul.height
            ul.paint.color = al(0xDDFFFFFF.toInt(), a)
            ul.paint.setShadowLayer(6f * u, 0f, 1f * u, al(0x88000000.toInt(), a))
            c.save(); c.translate(L, y); ul.draw(c); c.restore()
            y -= 22f * u
        }
        y -= r.naslov.height
        r.naslov.paint.color = al(0xFFFFFFFF.toInt(), a)
        r.naslov.paint.setShadowLayer(10f * u, 0f, 2f * u, al(0x88000000.toInt(), a))
        c.save(); c.translate(L, y); r.naslov.draw(c); c.restore()
        y -= 20f * u

        // ── vrijeme objave + NOVO ──
        var x = L
        val novo = v.stigla > 0 && sad - v.stigla < 30 * 60_000L
        if (novo) {
            tp.reset(); tp.isAntiAlias = true; tp.typeface = jak; tp.textSize = 22f * u; tp.letterSpacing = 0.12f
            val t = "NOVO"
            val tw = tp.measureText(t)
            val puls = if (sad - v.stigla < 5 * 60_000L) 0.75f + 0.25f * sin(sad / 300.0).toFloat() else 1f
            rr.set(x, y - 34f * u, x + tw + 28f * u, y + 4f * u)
            fp.color = al(CRVENA, a * puls)
            c.drawRoundRect(rr, 6f * u, 6f * u, fp)
            tp.color = al(0xFFFFFFFF.toInt(), a); c.drawText(t, x + 14f * u, y - 7f * u, tp)
            x = rr.right + 16f * u
        }
        tp.reset(); tp.isAntiAlias = true; tp.typeface = obican; tp.textSize = 23f * u; tp.letterSpacing = 0.03f
        tp.color = al(0xE6FFFFFF.toInt(), a); tp.setShadowLayer(6f * u, 0f, 1f * u, al(0xAA000000.toInt(), a))
        val kad = "${koliko(v.vrijeme, sad)}  ·  ${kadTacno(v.vrijeme, sad)}"
        c.drawText(kad, x, y - 7f * u, tp)
        if (novo) {
            x += tp.measureText(kad) + 16f * u
            tp.color = al(0xFFFF8A80.toInt(), a)
            c.drawText("·  stigla u ${satMin(v.stigla)}", x, y - 7f * u, tp)
        }
        tp.clearShadowLayer()
        c.restore()
    }

    private fun raspored(v: Vijest, sirina: Int, u: Float): Rasporedi {
        rasporedi[v]?.let { if (it.w == sirina) return it }
        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif; color = 0xFFFFFFFF.toInt()
            setShadowLayer(10f * u, 0f, 2f * u, 0x88000000.toInt())
        }
        var vel = 66f * u
        var nl: StaticLayout
        while (true) {
            np.textSize = vel
            nl = StaticLayout.Builder.obtain(v.naslov, 0, v.naslov.length, np, sirina)
                .setLineSpacing(0f, 1.06f).setIncludePad(false).build()
            if (nl.lineCount <= 3 || vel <= 42f * u) break
            vel -= 4f * u
        }
        if (nl.lineCount > 3) {
            nl = StaticLayout.Builder.obtain(v.naslov, 0, v.naslov.length, np, sirina)
                .setLineSpacing(0f, 1.06f).setIncludePad(false)
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        }
        val uvodTekst = v.uvod.takeIf { it.length > 20 && !it.startsWith(v.naslov.take(40)) }
        val ul = uvodTekst?.let { t ->
            val up = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = lagan; textSize = 25f * u; color = 0xDDFFFFFF.toInt()
                setShadowLayer(6f * u, 0f, 1f * u, 0x88000000.toInt())
            }
            StaticLayout.Builder.obtain(t, 0, t.length, up, sirina)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.12f).setIncludePad(false)
                .setMaxLines(if (nl.lineCount >= 3) 2 else 3).setEllipsize(TextUtils.TruncateAt.END).build()
        }
        return Rasporedi(sirina, nl, ul).also { rasporedi[v] = it }
    }

    /** Trake na dnu: koja je ovo vijest od 20 i koliko još traje. */
    private fun segmenti(c: Canvas, w: Float, h: Float, u: Float, sad: Long) {
        val n = lista.size
        val L = 64f * u; val gap = 6f * u
        val sw = (w - 2 * L - gap * (n - 1)) / n
        val y = h - 34f * u; val vis = 4f * u
        val p = ((sad - prikazanaOd).toFloat() / trajanje).coerceIn(0f, 1f)
        for (i in 0 until n) {
            val x = L + i * (sw + gap)
            val v = lista[i]
            val nova = v.stigla > 0 && sad - v.stigla < 30 * 60_000L
            val boja = if (nova) CRVENA else 0xFFFFFFFF.toInt()
            rr.set(x, y, x + sw, y + vis)
            fp.color = boja; fp.alpha = if (i < indeks) 190 else 64
            c.drawRoundRect(rr, vis / 2, vis / 2, fp)
            if (i == indeks) {
                rr.set(x, y, x + sw * p, y + vis)
                fp.alpha = 255; c.drawRoundRect(rr, vis / 2, vis / 2, fp)
            }
        }
        fp.alpha = 255
    }

    /** Sat, datum i stanje osvježavanja — gore desno, ne smjenjuje se sa karticama. */
    private fun okvir(c: Canvas, w: Float, h: Float, u: Float, sad: Long) {
        val (dx, dy) = pomak(u, sad)
        val R = w - 64f * u + dx
        val cal = Calendar.getInstance()
        tp.reset(); tp.isAntiAlias = true; tp.textAlign = Paint.Align.RIGHT
        tp.setShadowLayer(10f * u, 0f, 2f * u, 0xAA000000.toInt())
        tp.typeface = tanak; tp.textSize = 76f * u; tp.color = 0xFFFFFFFF.toInt()
        val sat = String.format("%d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        c.drawText(sat, R, 104f * u + dy, tp)
        tp.typeface = obican; tp.textSize = 21f * u; tp.color = 0xE6FFFFFF.toInt(); tp.letterSpacing = 0.03f
        val dan = "${daniPuni[cal.get(Calendar.DAY_OF_WEEK) - 1]}, ${cal.get(Calendar.DAY_OF_MONTH)}. ${mjeseci[cal.get(Calendar.MONTH)]}"
        c.drawText(dan, R, 138f * u + dy, tp)
        tp.textSize = 17f * u
        val stanje = when {
            radi -> { tp.color = 0xB3FFFFFF.toInt(); "provjeravam portale…" }
            greska -> { tp.color = 0xFFFFB74D.toInt(); "nema veze — pokušavam ponovo" }
            osvjezeno > 0 -> { tp.color = 0x99FFFFFF.toInt(); "provjereno u ${satMin(osvjezeno)}" }
            else -> ""
        }
        if (stanje.isNotEmpty()) c.drawText(stanje, R, 166f * u + dy, tp)
        tp.clearShadowLayer(); tp.textAlign = Paint.Align.LEFT
    }

    /** Traka „NOVA VIJEST" koja sklizne odozgo kad stigne nešto novo. */
    private fun baner(c: Canvas, w: Float, h: Float, u: Float, sad: Long) {
        val t = sad - banerOd
        if (banerOd == 0L || t > 8000) return
        if (t < 700) { // kratak bljesak
            fp.color = 0xFFFFFFFF.toInt(); fp.alpha = ((1f - t / 700f) * 70).toInt()
            c.drawRect(0f, 0f, w, h, fp); fp.alpha = 255
        }
        val ulaz = glatko((t / 450f).coerceIn(0f, 1f)) * (1f - glatko(((t - 7400) / 600f).coerceIn(0f, 1f)))
        tp.reset(); tp.isAntiAlias = true; tp.typeface = jak; tp.textSize = 26f * u; tp.letterSpacing = 0.10f
        val tw = tp.measureText(banerTekst)
        val bw = tw + 70f * u; val bh = 56f * u
        val x = (w - bw) / 2; val y = -bh + (bh + 26f * u) * ulaz
        rr.set(x, y, x + bw, y + bh)
        fp.color = 0x66000000; c.drawRoundRect(RectF(rr.left, rr.top + 4 * u, rr.right, rr.bottom + 4 * u), bh / 2, bh / 2, fp)
        fp.color = CRVENA; c.drawRoundRect(rr, bh / 2, bh / 2, fp)
        val puls = 0.5f + 0.5f * sin(sad / 250.0).toFloat()
        fp.color = 0xFFFFFFFF.toInt(); fp.alpha = (120 + 135 * puls).toInt()
        c.drawCircle(x + 30f * u, y + bh / 2, 7f * u, fp); fp.alpha = 255
        tp.color = 0xFFFFFFFF.toInt()
        c.drawText(banerTekst, x + 48f * u, y + bh / 2 + 9f * u, tp)
    }

    private fun prazno(c: Canvas, w: Float, h: Float, u: Float, sad: Long) {
        fp.color = 0xFFFFFFFF.toInt()
        fp.shader = LinearGradient(0f, 0f, w, h, 0xFF1A1D26.toInt(), 0xFF07080B.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fp); fp.shader = null
        tp.reset(); tp.isAntiAlias = true; tp.textAlign = Paint.Align.CENTER
        tp.typeface = serif; tp.textSize = 96f * u; tp.color = 0xFFFFFFFF.toInt()
        c.drawText("Vijesti", w / 2, h / 2, tp)
        tp.typeface = lagan; tp.textSize = 26f * u; tp.color = 0xB3FFFFFF.toInt()
        val tacke = ".".repeat(((sad / 400) % 4).toInt())
        c.drawText(if (greska) "Nema veze sa internetom — pokušavam ponovo" else "Učitavam najnovije vijesti$tacke",
            w / 2, h / 2 + 60f * u, tp)
        tp.textAlign = Paint.Align.LEFT
    }

    // ───────────────────────── slike ─────────────────────────

    private fun slika(v: Vijest): Bitmap? {
        val url = v.slika ?: return null
        kes[url]?.let { return it }
        if (url in uToku || url in neuspjele) return null
        uToku.add(url)
        val sirina = max(width, 1280)
        radnik.execute {
            var bmp: Bitmap? = null
            try {
                val f = File(dir, hes(url))
                if (!f.exists() || f.length() == 0L) {
                    val b = try { Mreza.bajtovi(url, 8_000_000) } catch (e: Exception) {
                        // svježa slika ponekad još ne postoji u toj veličini (Antena M) → og:image sa stranice
                        val og = Citac.ogSlika(Mreza.glavaStranice(v.link))
                        if (og == null || og == url) throw e
                        android.util.Log.w("Vijesti", "slika $url ne radi → $og")
                        Mreza.bajtovi(og, 8_000_000)
                    }
                    val tmp = File(dir, f.name + ".tmp"); tmp.writeBytes(b); tmp.renameTo(f)
                } else f.setLastModified(System.currentTimeMillis())
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var uz = 1
                while (o.outWidth / (uz * 2) >= sirina && o.outHeight / (uz * 2) >= sirina * 0.55) uz *= 2
                bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = uz })
                if (bmp == null) f.delete()
            } catch (e: Exception) {
                android.util.Log.w("Vijesti", "slika $url: ${e.message}")
            } catch (e: OutOfMemoryError) {
                android.util.Log.w("Vijesti", "slika prevelika $url")
            }
            glavna.post {
                uToku.remove(url)
                if (bmp != null) { kes[url] = bmp!!; ucitano[url] = System.currentTimeMillis() }
                else neuspjele.add(url)
            }
        }
        return null
    }

    private fun hes(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ───────────────────────── pomoćno ─────────────────────────

    /** Lagano pomjeranje teksta (nekoliko piksela u par minuta) — da se ekran ne „ureže". */
    private fun pomak(u: Float, sad: Long): Pair<Float, Float> {
        val f = (sad % 600_000L) / 600_000.0 * 2 * PI
        return Pair((sin(f) * 7 * u).toFloat(), (cos(f * 2) * 5 * u).toFloat())
    }

    private fun tamnije(boja: Int, f: Float): Int =
        Color.rgb((Color.red(boja) * f).toInt(), (Color.green(boja) * f).toInt(), (Color.blue(boja) * f).toInt())

    fun satMin(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return String.format("%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun koliko(t: Long, sad: Long): String {
        val m = ((sad - t) / 60_000L).coerceAtLeast(0)
        if (m < 1) return "upravo"
        if (m < 60) return "prije $m min"
        val s = m / 60
        if (s < 24) return "prije $s " + when {
            s % 10 == 1L && s % 100 != 11L -> "sat"
            s % 10 in 2..4 && s % 100 !in 12..14 -> "sata"
            else -> "sati"
        }
        val d = s / 24
        return if (d == 1L) "prije jednog dana" else "prije $d dana"
    }

    private fun kadTacno(t: Long, sad: Long): String {
        val a = Calendar.getInstance().apply { timeInMillis = t }
        val b = Calendar.getInstance().apply { timeInMillis = sad }
        val isti = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        b.add(Calendar.DAY_OF_YEAR, -1)
        val juce = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            isti -> "objavljeno u ${satMin(t)}"
            juce -> "juče u ${satMin(t)}"
            else -> "${a.get(Calendar.DAY_OF_MONTH)}.${a.get(Calendar.MONTH) + 1}. u ${satMin(t)}"
        }
    }
}
