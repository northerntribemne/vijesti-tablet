package com.jovan.vijesti

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import kotlin.math.min

/**
 * Čitač članka preko cijelog ekrana: velika slika sa naslovom, pa tekst u koloni
 * podešenoj za čitanje sa ~30 cm. ✕ gore desno zatvara i vraća na istu vijest.
 */
@SuppressLint("ViewConstructor")
class Citalac(ctx: Context, private val v: Vijest, private val zatvori: (gotovo: Boolean) -> Unit) : FrameLayout(ctx) {

    private val prefs = ctx.getSharedPreferences("vijesti", Context.MODE_PRIVATE)
    private var skala = prefs.getFloat("slova", 1f)
    private val glavna = Handler(Looper.getMainLooper())
    private val skrol = ScrollView(ctx)
    private val sadrzaj = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val kolona = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val napredak = View(ctx)
    private val tekstovi = ArrayList<Pair<TextView, Float>>()   // TextView + osnovna veličina (sp)
    private var zatvoren = false

    private val POZADINA = 0xFF15161A.toInt()
    private val TEKST = 0xFFDEDBD3.toInt()
    private val SIVO = 0xFF8D8A84.toInt()
    private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val serifJak = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansSrednji = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val uzak = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    /** posle 10 min bez dodira čitač se sam zatvori i vijesti nastave da se smjenjuju */
    private val neaktivno = Runnable { zatvoriSe() }

    init {
        setBackgroundColor(POZADINA)
        isClickable = true
        skrol.isVerticalScrollBarEnabled = false
        skrol.overScrollMode = View.OVER_SCROLL_NEVER
        skrol.addView(sadrzaj, ViewGroup.LayoutParams(-1, -2))
        addView(skrol, LayoutParams(-1, -1))
        skrol.viewTreeObserver.addOnScrollChangedListener { pomjereno() }

        napredak.setBackgroundColor(v.izvor.boja)
        addView(napredak, LayoutParams(0, dp(4f)).apply { gravity = Gravity.TOP or Gravity.START })

        // ── dugmad gore desno: A−  A+  ✕ ──
        val traka = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        traka.addView(dugme("A−", 22f) { promijeniSlova(-0.1f) })
        traka.addView(dugme("A+", 22f) { promijeniSlova(+0.1f) })
        traka.addView(dugme("✕", 30f, vece = true) { zatvoriSe() })
        addView(traka, LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(18f); rightMargin = dp(18f) })

        alpha = 0f
        animate().alpha(1f).setDuration(260).start()
        glavna.postDelayed(neaktivno, 10 * 60_000L)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) { glavna.removeCallbacks(neaktivno); glavna.postDelayed(neaktivno, 10 * 60_000L) }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (sadrzaj.childCount == 0 && w > 0) sagradi(w, h)
    }

    fun zatvoriSe() {
        if (zatvoren) return
        zatvoren = true
        glavna.removeCallbacks(neaktivno)
        zatvori(false)
        animate().alpha(0f).setDuration(220).withEndAction { zatvori(true) }.start()
    }

    // ───────────────────────── raspored ─────────────────────────

    private fun sagradi(w: Int, h: Int) {
        // ── zaglavlje sa slikom ──
        val glava = FrameLayout(context)
        val visina = (h * 0.62f).toInt()
        val slika = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        glava.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(tamnije(v.izvor.boja), 0xFF0B0C10.toInt()))
        glava.addView(slika, LayoutParams(-1, -1))
        glava.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x00000000, 0x99000000.toInt(), 0xF015161A.toInt(), POZADINA))
        }, LayoutParams(-1, -1))
        sadrzaj.addView(glava, LinearLayout.LayoutParams(-1, visina))

        val sirina = min(w - dp(80f), dp(780f))
        val bocno = (w - sirina) / 2
        val gornji = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // portal + rubrika
        val red = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        red.addView(TextView(context).apply {
            text = v.izvor.ime.uppercase(); typeface = uzak; letterSpacing = 0.06f
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            background = GradientDrawable().apply { setColor(v.izvor.boja); cornerRadius = dp(6f).toFloat() }
        })
        val rub = v.rubrika.ifEmpty { v.kategorija.ime }
        red.addView(TextView(context).apply {
            text = rub.uppercase(); typeface = sans; letterSpacing = 0.16f
            setTextColor(0xE6FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14f), 0, 0, 0)
        })
        gornji.addView(red)
        gornji.addView(tekst(v.naslov, 40f, serifJak, Color.WHITE, 1.12f).apply {
            setShadowLayer(dp(8f).toFloat(), 0f, dp(2f).toFloat(), 0x88000000.toInt())
        }, lp(top = 14f))
        gornji.addView(tekst(kadObjavljeno(), 16f, sans, 0xCCFFFFFF.toInt(), 1.2f), lp(top = 14f))
        glava.addView(gornji, LayoutParams(sirina, -2).apply { gravity = Gravity.BOTTOM; leftMargin = bocno; bottomMargin = dp(26f) })

        // ── kolona teksta ──
        sadrzaj.addView(kolona, LinearLayout.LayoutParams(sirina, -2).apply { leftMargin = bocno; bottomMargin = dp(90f) })
        if (v.uvod.isNotBlank()) {
            kolona.addView(tekst(v.uvod, 23f, sansSrednji, 0xFFF2EFE9.toInt(), 1.35f), lp(top = 18f))
        }
        kolona.addView(View(context).apply { setBackgroundColor(v.izvor.boja) },
            LinearLayout.LayoutParams(dp(64f), dp(4f)).apply { topMargin = dp(30f); bottomMargin = dp(30f) })
        val cekaj = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ProgressBar(context).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(36f), dp(36f)))
            addView(tekst("Učitavam članak…", 18f, sans, SIVO, 1.2f), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14f) })
        }
        kolona.addView(cekaj)

        // slika zaglavlja: najprije iz keša prikaza, inače sa mreže
        v.slika?.let { url -> ucitajSliku(url, w) { b -> slika.setImageBitmap(b); slika.alpha = 0f; slika.animate().alpha(1f).setDuration(400).start() } }

        Thread {
            val s = try { Clanak.preuzmi(v) } catch (e: Exception) {
                android.util.Log.w("Vijesti", "članak ${v.link}: ${e.message}"); null
            }
            glavna.post {
                if (zatvoren) return@post
                kolona.removeView(cekaj)
                if (s == null) {
                    kolona.addView(tekst("Tekst članka trenutno nije moguće učitati. Pokušaj ponovo malo kasnije.", 19f, serif, SIVO, 1.5f))
                } else popuni(s, w)
                kolona.addView(View(context).apply { setBackgroundColor(0x22FFFFFF) },
                    LinearLayout.LayoutParams(-1, dp(1f)).apply { topMargin = dp(40f) })
                kolona.addView(tekst("${v.izvor.ime}  ·  ${v.link.substringAfter("://").substringBefore('/').removePrefix("www.")}",
                    15f, sans, SIVO, 1.2f), lp(top = 14f))
                kolona.addView(TextView(context).apply {
                    text = "✕  Zatvori"; typeface = sansSrednji; setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER
                    setPadding(dp(28f), dp(14f), dp(28f), dp(14f))
                    background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(28f).toFloat() }
                    setOnClickListener { zatvoriSe() }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(34f); gravity = Gravity.CENTER_HORIZONTAL })
            }
        }.start()
    }

    private fun popuni(s: Clanak.Sadrzaj, w: Int) {
        for (b in s.blokovi) {
            when (b) {
                is Clanak.Blok.Pasus -> {
                    val t = tekst(b.tekst, 21f, serif, TEKST, 1.45f)
                    kolona.addView(t, lp(bottom = 30f))
                }
                is Clanak.Blok.Podnaslov -> kolona.addView(tekst(b.tekst, 24f, uzak, Color.WHITE, 1.25f), lp(top = 18f, bottom = 14f))
                is Clanak.Blok.Citat -> {
                    val okvir = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    okvir.addView(View(context).apply { setBackgroundColor(v.izvor.boja) }, LinearLayout.LayoutParams(dp(4f), -1))
                    okvir.addView(tekst(b.tekst, 22f, Typeface.create(Typeface.SERIF, Typeface.ITALIC), 0xFFF2EFE9.toInt(), 1.5f),
                        LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(22f) })
                    kolona.addView(okvir, lp(top = 10f, bottom = 28f))
                }
                is Clanak.Blok.Stavka -> {
                    val red = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    red.addView(tekst("•", 21f, serifJak, v.izvor.boja, 1.45f), LinearLayout.LayoutParams(dp(28f), -2))
                    red.addView(tekst(b.tekst, 21f, serif, TEKST, 1.45f), LinearLayout.LayoutParams(0, -2, 1f))
                    kolona.addView(red, lp(bottom = 12f))
                }
                is Clanak.Blok.Slika -> {
                    val iv = ImageView(context).apply {
                        adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(0x11FFFFFF); minimumHeight = dp(200f)
                    }
                    kolona.addView(iv, lp(top = 12f, bottom = if (b.opis != null) 8f else 28f).apply { width = -1 })
                    b.opis?.let { kolona.addView(tekst(it, 15f, sans, SIVO, 1.35f), lp(bottom = 28f)) }
                    ucitajSliku(b.url, w) { bmp -> iv.minimumHeight = 0; iv.setBackgroundColor(0); iv.setImageBitmap(bmp) }
                }
            }
        }
        // tekst lagano uplovi
        kolona.alpha = 0f; kolona.animate().alpha(1f).setDuration(350).start()
    }

    // ───────────────────────── pomoćno ─────────────────────────

    private fun tekst(t: CharSequence, sp: Float, tf: Typeface, boja: Int, prored: Float) = TextView(context).apply {
        text = t; typeface = tf; setTextColor(boja)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * skala)
        setLineSpacing(0f, prored)
        breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
        includeFontPadding = true
        tekstovi.add(Pair(this, sp))
    }

    private fun dugme(t: String, sp: Float, vece: Boolean = false, klik: () -> Unit) = TextView(context).apply {
        text = t; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        typeface = if (vece) sans else sansSrednji
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        val d = dp(if (vece) 68f else 58f)
        layoutParams = LinearLayout.LayoutParams(d, d).apply { leftMargin = dp(12f) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(0xA0000000.toInt()); setStroke(dp(1f), 0x44FFFFFF)
        }
        setOnClickListener { klik() }
    }

    private fun promijeniSlova(d: Float) {
        skala = (skala + d).coerceIn(0.8f, 1.6f)
        prefs.edit().putFloat("slova", skala).apply()
        for ((t, sp) in tekstovi) t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * skala)
    }

    private fun pomjereno() {
        val dijete = skrol.getChildAt(0) ?: return
        val raspon = dijete.height - skrol.height
        val k = if (raspon > 0) (skrol.scrollY.toFloat() / raspon).coerceIn(0f, 1f) else 0f
        napredak.layoutParams = napredak.layoutParams.apply { width = (this@Citalac.width * k).toInt() }
    }

    private fun lp(top: Float = 0f, bottom: Float = 0f) =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, resources.displayMetrics).toInt()

    private fun tamnije(boja: Int): Int =
        Color.rgb((Color.red(boja) * 0.5f).toInt(), (Color.green(boja) * 0.5f).toInt(), (Color.blue(boja) * 0.5f).toInt())

    private fun kadObjavljeno(): String {
        val c = Calendar.getInstance().apply { timeInMillis = v.vrijeme }
        val danas = Calendar.getInstance()
        val dan = when {
            c.get(Calendar.YEAR) == danas.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == danas.get(Calendar.DAY_OF_YEAR) -> "danas"
            c.get(Calendar.YEAR) == danas.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == danas.get(Calendar.DAY_OF_YEAR) - 1 -> "juče"
            else -> "%d.%d.%d.".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
        }
        val dodatno = if (v.takodje.isNotEmpty()) "   ·   piše i: " + v.takodje.joinToString(", ") { it.ime } else ""
        return "Objavljeno $dan u %02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + dodatno
    }

    private fun ucitajSliku(url: String, sirina: Int, gotovo: (Bitmap) -> Unit) {
        Thread {
            val bmp = try {
                val dir = File(context.cacheDir, "slike").apply { mkdirs() }
                val f = File(dir, MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
                if (!f.exists() || f.length() == 0L) {
                    val tmp = File(dir, f.name + ".tmp"); tmp.writeBytes(Mreza.bajtovi(url, 8_000_000)); tmp.renameTo(f)
                }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var uz = 1
                while (o.outWidth / (uz * 2) >= sirina) uz *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = uz })
            } catch (e: Exception) { android.util.Log.w("Vijesti", "slika čitača $url: ${e.message}"); null }
            catch (e: OutOfMemoryError) { null }
            if (bmp != null) glavna.post { if (!zatvoren) gotovo(bmp) }
        }.start()
    }
}
