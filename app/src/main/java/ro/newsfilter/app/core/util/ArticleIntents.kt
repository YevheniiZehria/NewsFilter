package ro.newsfilter.app.core.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Acțiuni „în afara aplicației” pentru un articol: deschiderea sursei originale
 * și distribuirea prin share sheet-ul nativ Android.
 */
object ArticleIntents {

    private const val TAG = "NewsFilter"
    private const val CHROME_PACKAGE = "com.android.chrome"

    /**
     * Deschide articolul în Chrome Custom Tabs, cu fallback sigur:
     *  1. Custom Tabs în Chrome (dacă e instalat) sau în browserul implicit compatibil;
     *  2. altfel, `Intent.ACTION_VIEW` (orice browser / aplicație care poate deschide link-ul);
     *  3. dacă nu există nimic instalat, un mesaj clar în loc de crash.
     *
     * @param toolbarColor culoarea barei Custom Tabs (ARGB), aliniată cu tema aplicației
     */
    fun openInBrowser(context: Context, url: String, toolbarColor: Int? = null) {
        val uri = Uri.parse(url.trim())
        if (uri.scheme !in setOf("http", "https")) {
            showToast(context, "Link-ul articolului nu este valid.")
            return
        }

        val customTabsPackage = CustomTabsClient.getPackageName(
            context,
            listOf(CHROME_PACKAGE),
            false
        ) ?: CustomTabsClient.getPackageName(context, null)

        if (customTabsPackage != null) {
            try {
                val builder = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                    .setUrlBarHidingEnabled(true)
                if (toolbarColor != null) {
                    builder.setDefaultColorSchemeParams(
                        CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build()
                    )
                }
                val customTabsIntent = builder.build()
                customTabsIntent.intent.setPackage(customTabsPackage)
                if (context !is Activity) customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabsIntent.launchUrl(context, uri)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Custom Tabs indisponibil, revin la ACTION_VIEW", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Custom Tabs refuzat, revin la ACTION_VIEW", e)
            }
        }

        try {
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Nu există nicio aplicație care să deschidă $url", e)
            showToast(context, "Nu am găsit un browser pentru a deschide articolul.")
        }
    }

    /**
     * Deschide share sheet-ul nativ (ACTION_SEND) cu titlul și link-ul articolului.
     */
    fun share(context: Context, title: String, url: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n\n$url")
        }
        val chooser = Intent.createChooser(sendIntent, "Distribuie articolul").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Nicio aplicație disponibilă pentru distribuire", e)
            showToast(context, "Nicio aplicație disponibilă pentru distribuire.")
        }
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
