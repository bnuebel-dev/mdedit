package app.mdedit

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Einstellungen. Das pCloud-Token liegt verschluesselt (Android Keystore), alles andere im Klartext.
 * Ist der sichere Speicher auf dem Geraet nicht verfuegbar, wird das Token bewusst NICHT im Klartext abgelegt.
 */
class Prefs(ctx: Context) {
    private val plain = ctx.getSharedPreferences("mdedit", Context.MODE_PRIVATE)

    private val secure: SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            ctx,
            "mdedit_secure",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    val secureAvailable: Boolean get() = secure != null

    var pcloudToken: String?
        get() = try {
            secure?.getString("token", null)
        } catch (e: Exception) {
            null
        }
        set(v) {
            secure?.edit()?.putString("token", v)?.apply()
        }

    var pcloudHost: String
        get() = plain.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
        set(v) {
            plain.edit().putString("host", v).apply()
        }

    val treeUris: List<String>
        get() = (plain.getStringSet("trees", emptySet()) ?: emptySet()).sorted()

    fun addTree(s: String) {
        val set = (plain.getStringSet("trees", emptySet()) ?: emptySet()).toMutableSet()
        set.add(s)
        plain.edit().putStringSet("trees", set).apply()
    }

    fun removeTree(s: String) {
        val set = (plain.getStringSet("trees", emptySet()) ?: emptySet()).toMutableSet()
        set.remove(s)
        plain.edit().putStringSet("trees", set).apply()
    }

    companion object {
        const val HOST_EU = "eapi.pcloud.com"
        const val HOST_US = "api.pcloud.com"
        const val DEFAULT_HOST = HOST_EU
    }
}
