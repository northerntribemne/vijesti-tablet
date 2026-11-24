package com.jovan.vijesti

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Preuzimanje sa interneta.
 * Android 6 nema novije korijenske sertifikate (ISRG/Let's Encrypt, GTS), pa ih app nosi sa sobom
 * (assets/korijeni.pem) i prihvata lanac ako ga potvrdi ILI sistem ILI ti ugrađeni korijeni.
 */
object Mreza {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 6.0; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36 VijestiTablet/1.0"

    fun pripremi(ctx: Context) {
        try {
            val sistem = trustManager(null)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val cf = CertificateFactory.getInstance("X.509")
            ctx.assets.open("korijeni.pem").use { ulaz ->
                cf.generateCertificates(ulaz).forEachIndexed { i, c -> ks.setCertificateEntry("k$i", c) }
            }
            val ugradjeni = trustManager(ks)
            val oba = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    sistem.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try { sistem.checkServerTrusted(chain, authType) }
                    catch (e: Exception) { ugradjeni.checkServerTrusted(chain, authType) }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    sistem.acceptedIssuers + ugradjeni.acceptedIssuers
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(oba), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
        } catch (e: Exception) {
            android.util.Log.w("Vijesti", "ugrađeni sertifikati nijesu učitani", e)
        }
    }

    private fun trustManager(ks: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    fun bajtovi(adresa: String, max: Int = 6_000_000): ByteArray {
        var url = adresa
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "sr-ME,sr;q=0.9,en;q=0.5")
            try {
                val kod = c.responseCode
                if (kod in 301..308) {
                    // ručno praćenje preusmjeravanja (HttpURLConnection ne prelazi http↔https)
                    url = URL(URL(url), c.getHeaderField("Location") ?: throw RuntimeException("HTTP $kod bez Location")).toString()
                    return@repeat
                }
                if (kod != 200) throw RuntimeException("HTTP $kod $url")
                val izlaz = ByteArrayOutputStream()
                c.inputStream.use { ulaz ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = ulaz.read(buf); if (n < 0) break
                        izlaz.write(buf, 0, n)
                        if (izlaz.size() > max) throw RuntimeException("prevelik odgovor $url")
                    }
                }
                return izlaz.toByteArray()
            } finally { c.disconnect() }
        }
        throw RuntimeException("previše preusmjeravanja $adresa")
    }

    fun tekst(adresa: String): String = String(bajtovi(adresa), Charsets.UTF_8)

    /** Samo početak stranice (do </head>) — dovoljno za og:image / og:title. */
    fun glavaStranice(adresa: String): String {
        val t = String(bajtovi(adresa, 3_000_000), Charsets.UTF_8)
        val k = t.indexOf("</head>", ignoreCase = true)
        return if (k > 0) t.substring(0, k) else t.take(200_000)
    }
}
