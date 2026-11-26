package ro.newsfilter.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityOptionsCompat
import dagger.hilt.android.AndroidEntryPoint
import ro.newsfilter.app.ui.NewsFilterAppContent

/**
 * A doua Activity a aplicației: experiența principală, disponibilă doar
 * utilizatorilor autentificați (Onboarding la prima rulare → Feed, Salvate,
 * Setări, Detaliu — navigare Compose în interiorul acestei Activity).
 *
 * Gardă de sesiune: `NewsFilterAppContent` observă `isLoggedIn` din DataStore.
 * Când sesiunea lipsește (Deconectare din Feed / Setări sau deschidere dintr-o
 * notificare după logout), se apelează [openAuthActivity], care curăță task-ul
 * și revine la [AuthActivity].
 *
 * enableEdgeToEdge() permite ca UI-ul Compose să se extindă sub bara de status
 * și bara de navigare. @AndroidEntryPoint activează injecția Hilt.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var isLeaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NewsFilterAppContent(onSessionEnded = ::openAuthActivity)
        }
    }

    /** Revine la ecranul de autentificare și golește back stack-ul. */
    private fun openAuthActivity() {
        if (isLeaving) return
        isLeaving = true
        val intent = Intent(this, AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val options = ActivityOptionsCompat.makeCustomAnimation(
            this,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
        startActivity(intent, options.toBundle())
        finish()
    }
}
