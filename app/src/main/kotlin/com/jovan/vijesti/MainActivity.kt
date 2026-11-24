package com.jovan.vijesti

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {

    private lateinit var prikaz: Prikaz
    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())
    private var aktivan = false
    @Volatile private var preuzimam = false
    private var dodirX = 0f

    /** link → kad je vijest prvi put viđena (0 = bila je tu od ranije) */
    private val vidjeno = LinkedHashMap<String, Long>()
    /** dopune sa samih stranica (pun naslov, og:image) da se ne preuzimaju svaki put */
    private val dopune = ConcurrentHashMap<String, Pair<String, String?>>()

    private val provjera = Runnable { osvjezi() }
    private val minutni = object : Runnable {
        override fun run() { svjetlina(); sakrijSistem(); h.postDelayed(this, 30_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        prefs = getSharedPreferences("vijesti", MODE_PRIVATE)
        Thread { Mreza.pripremi(applicationContext) }.apply { start(); join(3000) }
        prikaz = Prikaz(this)
        setContentView(prikaz)
        prikaz.trajanje = prefs.getInt("trajanje", 15) * 1000L
        ucitajStanje()
        prikaz.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) dodirX = e.x; false }
        prikaz.setOnClickListener { if (dodirX < prikaz.width * 0.3f) prikaz.nazad() else prikaz.naprijed() }
        prikaz.setOnLongClickListener { podesavanja(); true }
    }

    private fun ucitajStanje() {
        try {
            JSONArray(prefs.getString("vidjeno", "[]")).let { a ->
                for (i in 0 until a.length()) a.getJSONArray(i).let { vidjeno[it.getString(0)] = it.getLong(1) }
            }
            val a = JSONArray(prefs.getString("lista", "[]"))
            val l = (0 until a.length()).mapNotNull { Vijest.izJson(a.getJSONObject(it)) }
            prikaz.osvjezeno = prefs.getLong("osvjezeno", 0L)
            if (l.isNotEmpty()) prikaz.postavi(l, emptyList())
        } catch (_: Exception) {}
    }

    private fun minuta() = prefs.getInt("provjera", 2)

    override fun onResume() {
        super.onResume()
        aktivan = true
        sakrijSistem()
        h.removeCallbacks(minutni); h.post(minutni)
        h.removeCallbacks(provjera); h.postDelayed(provjera, 800L)
    }

    override fun onPause() {
        super.onPause()
        aktivan = false
        h.removeCallbacks(minutni); h.removeCallbacks(provjera)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) sakrijSistem()
    }

    @Suppress("DEPRECATION")
    private fun sakrijSistem() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    /** Noću (23–6 h) se ekran zatamni. */
    private fun svjetlina() {
        val sat = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val nocu = prefs.getBoolean("zatamni", true) && (sat >= 23 || sat < 6)
        val lp = window.attributes
        val cilj = if (nocu) 0.05f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (lp.screenBrightness != cilj) { lp.screenBrightness = cilj; window.attributes = lp }
    }

    private fun ukljuceni(): List<Izvor> {
        val iskljuceni = prefs.getStringSet("iskljuceni", emptySet()) ?: emptySet()
        return Izvor.values().filter { it.name !in iskljuceni }
    }

    // ───────────────────────── preuzimanje ─────────────────────────

    private fun osvjezi() {
        if (preuzimam) return
        preuzimam = true
        prikaz.radi = true
        val izvori = ukljuceni()
        Thread {
            val sve = java.util.Collections.synchronizedList(ArrayList<Vijest>())
            val uspjeli = java.util.Collections.synchronizedSet(HashSet<Izvor>())
            val niti = izvori.map { iz ->
                Thread {
                    try { sve.addAll(Citac.preuzmi(iz)); uspjeli.add(iz) }
                    catch (e: Exception) { android.util.Log.w("Vijesti", "${iz.ime}: ${e.message}") }
                }.apply { start() }
            }
            niti.forEach { it.join(40_000L) }

            var lista: List<Vijest> = emptyList()
            val nove = ArrayList<Vijest>()
            if (uspjeli.isNotEmpty()) {
                // dopune ranije preuzete — prije spajanja, da se uporede puni naslovi
                synchronized(sve) {
                    for (v in sve) dopune[v.link]?.let { (n, s) -> v.naslov = n; if (v.slika == null) v.slika = s }
                }
                lista = Citac.spoji(ArrayList(sve), 20)
                for (v in lista) if (!dopune.containsKey(v.link)) {
                    Citac.dopuni(v); dopune[v.link] = Pair(v.naslov, v.slika)
                }
                if (dopune.size > 400) dopune.keys.retainAll(lista.map { it.link }.toSet())

                val sad = System.currentTimeMillis()
                val ranijiIzvori = prefs.getStringSet("ucitani", emptySet()) ?: emptySet()
                for (g in lista) {
                    val poznati = g.sviLinkovi.mapNotNull { vidjeno[it] }
                    g.stigla = when {
                        poznati.isNotEmpty() -> poznati.min()
                        // prvi put sa ovog portala, ili stara vijest → nije „nova"
                        g.izvor.name !in ranijiIzvori || sad - g.vrijeme > 3 * 3_600_000L -> 0L
                        else -> { nove.add(g); sad }
                    }
                }
                synchronized(sve) {
                    for (v in sve) if (!vidjeno.containsKey(v.link)) vidjeno[v.link] = 0L
                }
                for (g in lista) for (l in g.sviLinkovi) vidjeno[l] = g.stigla
                while (vidjeno.size > 800) vidjeno.remove(vidjeno.keys.first())

                prefs.edit()
                    .putString("vidjeno", JSONArray(vidjeno.entries.map { JSONArray().put(it.key).put(it.value) }).toString())
                    .putString("lista", JSONArray(lista.map { it.uJson() }).toString())
                    .putLong("osvjezeno", sad)
                    .putStringSet("ucitani", ranijiIzvori + uspjeli.map { it.name })
                    .apply()
            }
            val ok = uspjeli.isNotEmpty()
            runOnUiThread {
                preuzimam = false
                prikaz.radi = false
                prikaz.greska = !ok
                if (ok) { prikaz.osvjezeno = System.currentTimeMillis(); prikaz.postavi(lista, nove) }
                if (aktivan) {
                    h.removeCallbacks(provjera)
                    h.postDelayed(provjera, if (ok) minuta() * 60_000L else 60_000L)
                }
            }
        }.start()
    }

    // ───────────────────────── podešavanja (dugi pritisak) ─────────────────────────

    private fun podesavanja() {
        val trajanja = intArrayOf(8, 10, 15, 20, 30, 45)
        val provjere = intArrayOf(1, 2, 3, 5, 10)
        val stavke = arrayOf(
            "Portali: " + ukljuceni().joinToString(", ") { it.ime },
            "Svaka vijest na ekranu: ${prefs.getInt("trajanje", 15)} s",
            "Provjera novih vijesti: svakih ${minuta()} min",
            "Noćno zatamnjenje (23–6 h): " + if (prefs.getBoolean("zatamni", true)) "uključeno" else "isključeno",
            "Pokreni pri paljenju tableta: " + if (prefs.getBoolean("boot", true)) "da" else "ne",
            "Provjeri vijesti sada",
            "Zatvori aplikaciju"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Podešavanja")
            .setItems(stavke) { _, i ->
                when (i) {
                    0 -> izaberiPortale()
                    1 -> izbor("Koliko dugo jedna vijest", trajanja.map { "$it sekundi" }, trajanja.indexOf(prefs.getInt("trajanje", 15))) { j ->
                        prefs.edit().putInt("trajanje", trajanja[j]).apply(); prikaz.trajanje = trajanja[j] * 1000L
                    }
                    2 -> izbor("Koliko često provjeravati", provjere.map { "svakih $it min" }, provjere.indexOf(minuta())) { j ->
                        prefs.edit().putInt("provjera", provjere[j]).apply()
                        h.removeCallbacks(provjera); h.postDelayed(provjera, provjere[j] * 60_000L)
                    }
                    3 -> { prefs.edit().putBoolean("zatamni", !prefs.getBoolean("zatamni", true)).apply(); svjetlina() }
                    4 -> prefs.edit().putBoolean("boot", !prefs.getBoolean("boot", true)).apply()
                    5 -> { Toast.makeText(this, "Provjeravam portale…", Toast.LENGTH_SHORT).show(); osvjezi() }
                    6 -> finish()
                }
            }
            .setOnDismissListener { sakrijSistem() }
            .show()
    }

    private fun izbor(naslov: String, opcije: List<String>, tekuca: Int, gotovo: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(naslov)
            .setSingleChoiceItems(opcije.toTypedArray(), tekuca) { d, j -> gotovo(j); d.dismiss() }
            .setOnDismissListener { sakrijSistem() }
            .show()
    }

    private fun izaberiPortale() {
        val svi = Izvor.values()
        val stanje = BooleanArray(svi.size) { svi[it] in ukljuceni() }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Sa kojih portala")
            .setMultiChoiceItems(svi.map { it.ime }.toTypedArray(), stanje) { _, j, da -> stanje[j] = da }
            .setPositiveButton("Sačuvaj") { _, _ ->
                if (stanje.none { it }) { Toast.makeText(this, "Bar jedan portal mora ostati", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                prefs.edit().putStringSet("iskljuceni", svi.filterIndexed { k, _ -> !stanje[k] }.map { it.name }.toSet()).apply()
                osvjezi()
            }
            .setNegativeButton("Otkaži", null)
            .setOnDismissListener { sakrijSistem() }
            .show()
    }
}

/** Kad se tablet upali, otvori vijesti (sa malim zakašnjenjem, da budu iznad ostalih aplikacija). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.getSharedPreferences("vijesti", Context.MODE_PRIVATE).getBoolean("boot", true)) return
        val kraj = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } finally { kraj.finish() }
        }, 6000L)
    }
}
