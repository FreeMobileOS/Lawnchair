/*
 *     This file is part of Lawnchair Launcher.
 *
 *     Lawnchair Launcher is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Lawnchair Launcher is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Lawnchair Launcher.  If not, see <https://www.gnu.org/licenses/>.
 */

package ch.deletescape.lawnchair.colors

import android.content.Context
import android.graphics.Color.*
import android.text.TextUtils
import ch.deletescape.lawnchair.*
import ch.deletescape.lawnchair.colors.resolvers.*
import ch.deletescape.lawnchair.util.SingletonHolder
import ch.deletescape.lawnchair.util.ThemedContextProvider
import com.android.launcher3.Utilities

class ColorEngine private constructor(val context: Context) : LawnchairPreferences.OnPreferenceChangeListener {

    private val prefs by lazy { Utilities.getLawnchairPrefs(context) }
    private val colorListeners = mutableMapOf<String, MutableSet<OnColorChangeListener>>()

    private val resolverMap = mutableMapOf<String, LawnchairPreferences.StringBasedPref<ColorResolver>>()
    private val resolverCache = mutableMapOf<String, ColorResolver>()

    private var _accentResolver by getOrCreateResolver(Resolvers.ACCENT)
    val accentResolver get() = _accentResolver
    val accent get() = accentResolver.resolveColor()
    val accentForeground get() = accentResolver.computeForegroundColor()

    override fun onValueChanged(key: String, prefs: LawnchairPreferences, force: Boolean) {
        if (!force) {
            onColorChanged(key, getOrCreateResolver(key).onGetValue())
        }
    }

    private fun onColorChanged(key: String, colorResolver: ColorResolver) {
        runOnMainThread { colorListeners[key]?.forEach { it.onColorChange(ResolveInfo(key, colorResolver)) } }
    }

    fun addColorChangeListeners(listener: OnColorChangeListener, vararg keys: String) {
        if (keys.isEmpty()) {
            throw RuntimeException("At least one key is required")
        }
        for (key in keys) {
            if (colorListeners[key] == null) {
                colorListeners[key] = createWeakSet()
                prefs.addOnPreferenceChangeListener(this, key)
            }
            colorListeners[key]?.add(listener)
            val resolver by getOrCreateResolver(key)
            listener.onColorChange(ResolveInfo(key, resolver))
        }
    }

    fun removeColorChangeListeners(listener: OnColorChangeListener, vararg keys: String) {
        if (keys.isEmpty()) {
            throw RuntimeException("At least one key is required")
        }
        for (key in keys) {
            colorListeners[key]?.remove(listener)
            if (colorListeners[key]?.isEmpty() == true) {
                colorListeners.remove(key)
                prefs.removeOnPreferenceChangeListener(key, this)
            }
        }
    }

    private fun createResolverPref(key: String, defaultValue: ColorResolver = Resolvers.getDefaultResolver(key, context, this)) =
            prefs.StringBasedPref(key, defaultValue, prefs.doNothing, { string -> createColorResolver(key, string) },
                    ColorResolver::toString, ColorResolver::onDestroy)

    fun createColorResolverNullable(key: String, string: String): ColorResolver? {
        var resolver: ColorResolver? = null
        try {
            val parts = string.split("|")
            val className = parts[0]
            val args = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()

            val clazz = Class.forName(className)
            val constructor = clazz.getConstructor(ColorResolver.Config::class.java)
            resolver = constructor.newInstance(ColorResolver.Config(key, this, ::onColorChanged, args)) as ColorResolver
        } catch (e: IllegalStateException) {
        } catch (e: ClassNotFoundException) {
        } catch (e: InstantiationException) {
        }
        return resolver
    }

    fun createColorResolver(key: String, string: String): ColorResolver {
        val cacheKey = "$key@$string"
        // Prevent having to expensively use reflection every time
        if (resolverCache.containsKey(cacheKey)) return resolverCache[cacheKey]!!.also { it.ensureIsListening() }
        val resolver = createColorResolverNullable(key, string)
        return (resolver ?: Resolvers.getDefaultResolver(key, context, this)).also {
            resolverCache[cacheKey] = it
            it.ensureIsListening()
        }
    }

    @JvmOverloads
    fun getOrCreateResolver(key: String, defaultValue: ColorResolver = Resolvers.getDefaultResolver(key, context, this)): LawnchairPreferences.StringBasedPref<ColorResolver> {
        return resolverMap[key] ?: createResolverPref(key, defaultValue).also {
            resolverMap[key] = it
        }
    }

    fun setColor(resolver: String, color: Int) {
        prefs.sharedPrefs.edit().putString(resolver, (if (alpha(color) < 0xFF) {
            // ARGB
            arrayOf(
                    ARGBColorResolver::class.java.name,
                    alpha(color).toString(),
                    red(color).toString(),
                    green(color).toString(),
                    blue(color).toString()
            )
        } else {
            // RGB
            arrayOf(
                    RGBColorResolver::class.java.name,
                    red(color).toString(),
                    green(color).toString(),
                    blue(color).toString()
            )
        }).joinToString("|")).apply()
    }

    companion object : SingletonHolder<ColorEngine, Context>(ensureOnMainThread(useApplicationContext(::ColorEngine))) {
        @JvmStatic
        override fun getInstance(arg: Context) = super.getInstance(arg)
    }

    interface OnColorChangeListener {

        fun onColorChange(resolveInfo: ResolveInfo)
    }

    internal class Resolvers {
        companion object {
            const val ACCENT = "pref_accentColorResolver"
            const val HOTSEAT_QSB_BG = "pref_hotseatQsbColorResolver"
            const val ALLAPPS_QSB_BG = "pref_allappsQsbColorResolver"
            const val ALLAPPS_ICON_LABEL = "pref_allAppsLabelColorResolver"
            const val WORKSPACE_ICON_LABEL = "pref_workspaceLabelColorResolver"
            const val DOCK_BACKGROUND = "pref_dockBackgroundColorResolver"
            const val ALLAPPS_BACKGROUND = "pref_allAppsBackgroundColorResolver"

            fun getDefaultResolver(key: String, context: Context, engine: ColorEngine): ColorResolver {
                return when (key) {
                    HOTSEAT_QSB_BG -> {
                        DockQsbAutoResolver(createConfig(key, engine))
                    }
                    ALLAPPS_QSB_BG -> {
                        DrawerQsbAutoResolver(createConfig(key, engine))
                    }
                    ALLAPPS_ICON_LABEL -> {
                        DrawerLabelAutoResolver(createConfig(key, engine))
                    }
                    WORKSPACE_ICON_LABEL -> {
                        WorkspaceLabelAutoResolver(createConfig(key, engine))
                    }
                    DOCK_BACKGROUND, ALLAPPS_BACKGROUND -> {
                        ShelfBackgroundAutoResolver(createConfig(key, engine))
                    }
                    ACCENT -> engine.createColorResolver(key, LawnchairConfig.getInstance(context).defaultColorResolver)
                    else -> engine.createColorResolver(key, LawnchairConfig.getInstance(context).defaultColorResolver)
                }
            }

            private fun createConfig(key: String, engine: ColorEngine)
                    = ColorResolver.Config(key, engine, engine::onColorChanged)
        }
    }

    abstract class ColorResolver(val config: Config) : ThemedContextProvider.Listener {

        private var listening = false
        val engine get() = config.engine
        val args get() = config.args
        open val isCustom = false
        open val themeAware = false

        val context get() = engine.context

        private val themedContextProvider by lazy { ThemedContextProvider(context, this) }
        val launcherThemeContext get() = themedContextProvider.get()

        abstract fun resolveColor(): Int

        abstract fun getDisplayName(): String

        override fun toString() = TextUtils.join("|", listOf(this::class.java.name) + args) as String

        fun computeForegroundColor() = resolveColor().foregroundColor

        fun computeLuminance() = resolveColor().luminance

        fun computeIsDark() = resolveColor().isDark

        fun ensureIsListening() {
            if (!listening) {
                startListening()
            }
        }

        open fun startListening() {
            listening = true
            if (themeAware) {
                themedContextProvider.startListening()
            }
        }

        open fun stopListening() {
            listening = false
            if (themeAware) {
                themedContextProvider.stopListening()
            }
        }

        fun notifyChanged() {
            config.listener?.invoke(config.key, this)
        }

        fun onDestroy() {
            if (listening) {
                stopListening()
            }
        }

        override fun onThemeChanged() {
            notifyChanged()
        }

        class Config(
                val key: String,
                val engine: ColorEngine,
                val listener: ((String, ColorResolver) -> Unit)? = null,
                val args: List<String> = emptyList())
    }

    class ResolveInfo(val key: String, resolver: ColorResolver) {

        val color = resolver.resolveColor()
        val foregroundColor by lazy { color.foregroundColor }
        val luminance = color.luminance
        val isDark = luminance < 0.5f

        val resolverClass = resolver::class.java
    }
}
