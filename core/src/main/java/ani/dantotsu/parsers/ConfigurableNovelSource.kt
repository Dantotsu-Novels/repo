package ani.dantotsu.parsers

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface ConfigurableNovelSource {
    fun getSourcePreferences(): SharedPreferences =
        Injekt.get<Application>().getSharedPreferences(preferenceKey(), Context.MODE_PRIVATE)

    fun setupPreferenceScreen(screen: PreferenceScreen)
}

fun ConfigurableNovelSource.preferenceKey(): String =
    "novel_source_${this.javaClass.simpleName}"

fun ConfigurableNovelSource.sourcePreferences(): SharedPreferences = getSourcePreferences()
