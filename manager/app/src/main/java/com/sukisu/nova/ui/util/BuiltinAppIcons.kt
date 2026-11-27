package com.sukisu.nova.ui.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.sukisu.nova.R
import com.sukisu.nova.ui.MainActivity

@Immutable
data class BuiltinAppIcon(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:DrawableRes val iconRes: Int,
    @param:DrawableRes val foregroundRes: Int,
    
    val aliasClassName: String?,
)


object BuiltinAppIcons {
    const val DEFAULT_ID = "default"
    const val SUKISU_ID = "sukisu"
    const val AILISI_ID = "ailisi"
    const val BAIZI_ID = "baizi"
    const val JUWANG_ID = "juwang"
    const val KAIYI_ID = "kaiyi"
    const val PULANA_ID = "pulana"
    const val XIANGUO_ID = "xianguo"

    // 旧版内置图标 id：已从选择列表移除，仅用于识别历史设置与关闭遗留的启动别名。
    const val KERNELSU_ID = "kernelsu"
    const val LOGO_ID = "sukisu_logo"
    const val ZAKO_ID = "zako"

    private const val TAG = "BuiltinAppIcons"
    private val mainActivityName = MainActivity::class.java.name

    val all: List<BuiltinAppIcon> = listOf(
        BuiltinAppIcon(
            id = DEFAULT_ID,
            nameRes = R.string.app_icon_builtin_default,
            iconRes = R.mipmap.ic_launcher_next,
            foregroundRes = R.mipmap.ic_launcher_next_fore,
            aliasClassName = mainActivityName + "AliasDefault",
        ),
        BuiltinAppIcon(
            id = SUKISU_ID,
            nameRes = R.string.app_icon_builtin_sukisu,
            iconRes = R.mipmap.ic_launcher_sukisu,
            foregroundRes = R.mipmap.ic_launcher_sukisu,
            aliasClassName = mainActivityName + "AliasSukiSU",
        ),
        BuiltinAppIcon(
            id = AILISI_ID,
            nameRes = R.string.app_icon_builtin_ailisi,
            iconRes = R.mipmap.ic_launcher_ailisi,
            foregroundRes = R.mipmap.ic_launcher_ailisi,
            aliasClassName = mainActivityName + "AliasAilisi",
        ),
        BuiltinAppIcon(
            id = BAIZI_ID,
            nameRes = R.string.app_icon_builtin_baizi,
            iconRes = R.mipmap.ic_launcher_baizi,
            foregroundRes = R.mipmap.ic_launcher_baizi,
            aliasClassName = mainActivityName + "AliasBaizi",
        ),
        BuiltinAppIcon(
            id = JUWANG_ID,
            nameRes = R.string.app_icon_builtin_juwang,
            iconRes = R.mipmap.ic_launcher_juwang,
            foregroundRes = R.mipmap.ic_launcher_juwang,
            aliasClassName = mainActivityName + "AliasJuwang",
        ),
        BuiltinAppIcon(
            id = KAIYI_ID,
            nameRes = R.string.app_icon_builtin_kaiyi,
            iconRes = R.mipmap.ic_launcher_kaiyi,
            foregroundRes = R.mipmap.ic_launcher_kaiyi,
            aliasClassName = mainActivityName + "AliasKaiyi",
        ),
        BuiltinAppIcon(
            id = PULANA_ID,
            nameRes = R.string.app_icon_builtin_pulana,
            iconRes = R.mipmap.ic_launcher_pulana,
            foregroundRes = R.mipmap.ic_launcher_pulana,
            aliasClassName = mainActivityName + "AliasPulana",
        ),
        BuiltinAppIcon(
            id = XIANGUO_ID,
            nameRes = R.string.app_icon_builtin_xianguo,
            iconRes = R.mipmap.ic_launcher_xianguo,
            foregroundRes = R.mipmap.ic_launcher_xianguo,
            aliasClassName = mainActivityName + "AliasXianguo",
        ),
    )

    fun find(id: String?): BuiltinAppIcon = all.firstOrNull { it.id == id } ?: all.first()

    
    fun apply(
        context: Context,
        icon: BuiltinAppIcon,
        dynamicColor: Boolean = false,
    ) {
        val pm = context.packageManager
        val main = ComponentName(context, mainActivityName)
        val selectedClass = componentClassName(icon, dynamicColor)
        val target = ComponentName(context, selectedClass)

        setEnabled(pm, main, true)
        setEnabled(pm, target, true)
        allAliasClassNames()
            .filter { it != selectedClass }
            .forEach { setEnabled(pm, ComponentName(context, it), false) }
    }

    /**
     * Applies [icon] only when the component states do not already match it.
     *
     * Call this only while the manager is not starting or visible. Switching launcher
     * components can restart the process on some ROMs, and disabling the alias that is
     * currently launching the task kills the first launch after an update — restore from
     * [com.sukisu.nova.data.repository.AppIconUpdateReceiver] or from the background
     * lifecycle callback in `KernelSUApplication` instead of from startup.
     */
    fun applyIfNeeded(
        context: Context,
        icon: BuiltinAppIcon,
        dynamicColor: Boolean = false,
    ) {
        if (!isApplied(context, icon, dynamicColor)) {
            apply(context, icon, dynamicColor)
        }
    }

    fun isApplied(
        context: Context,
        icon: BuiltinAppIcon,
        dynamicColor: Boolean = false,
    ): Boolean {
        val pm = context.packageManager
        if (!isComponentEnabled(pm, ComponentName(context, mainActivityName))) {
            return false
        }
        val selectedClass = componentClassName(icon, dynamicColor)
        return isComponentEnabled(pm, ComponentName(context, selectedClass)) && allAliasClassNames()
            .filter { it != selectedClass }
            .none { isComponentEnabled(pm, ComponentName(context, it)) }
    }

    private fun isComponentEnabled(pm: PackageManager, component: ComponentName): Boolean {
        val state = try {
            pm.getComponentEnabledSetting(component)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot read the enabled state of $component", t)
            return false
        }
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> try {
                pm.getActivityInfo(component, 0).enabled
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun componentClassName(
        icon: BuiltinAppIcon,
        dynamicColor: Boolean,
    ): String {
        if (dynamicColor) {
            return icon.aliasClassName ?: (mainActivityName + "AliasDefault")
        }
        return mainActivityName + "Alias" + aliasSuffix(icon) + "Static"
    }

    private fun allAliasClassNames(): List<String> = buildList {
        all.forEach { icon ->
            val base = mainActivityName + "Alias" + aliasSuffix(icon)
            add(icon.aliasClassName ?: (mainActivityName + "AliasDefault"))
            add(base + "Static")
        }
        // 已移除的内置图标：别名仍可能处于启用状态（老版本的安装留下的），一并关掉。
        legacyAliasClassNames().forEach(::add)
    }

    private fun legacyAliasClassNames(): List<String> = listOf(
        mainActivityName + "Alias",
        mainActivityName + "AliasStatic",
        mainActivityName + "AliasLogo",
        mainActivityName + "AliasLogoStatic",
        mainActivityName + "AliasZako",
        mainActivityName + "AliasZakoStatic",
    )

    private fun aliasSuffix(icon: BuiltinAppIcon): String = when (icon.id) {
        DEFAULT_ID -> "Default"
        SUKISU_ID -> "SukiSU"
        AILISI_ID -> "Ailisi"
        BAIZI_ID -> "Baizi"
        JUWANG_ID -> "Juwang"
        KAIYI_ID -> "Kaiyi"
        PULANA_ID -> "Pulana"
        XIANGUO_ID -> "Xianguo"
        KERNELSU_ID -> ""
        LOGO_ID -> "Logo"
        ZAKO_ID -> "Zako"
        else -> "Default"
    }

    private fun setEnabled(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        try {
            pm.setComponentEnabledSetting(
                component,
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot change the enabled state of $component", t)
        }
    }
}
