package com.alarmhub.app.permissions

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * PRD FR-5.2 / FR-5.3 — 「一键跳转」and its fallback.
 *
 * The candidate lists exist because HyperOS has no stable, documented intent for its own permission
 * pages: the same switch has been reachable through different components on different releases, and
 * some of them are reachable only as an explicit component while others answer a public-looking
 * action string. So each row gets an **ordered** list, tried one at a time, and the outcome is
 * always reported back honestly:
 *
 * | outcome | meaning | contract result |
 * |---|---|---|
 * | a candidate opened | the vendor/system page is on screen | `opened = true, fallback = false` |
 * | nothing opened | every candidate threw, we opened the app's own system detail page | `opened = false, fallback = true` |
 *
 * A "candidate" that throws [ActivityNotFoundException] locally (stock Android, no MIUI security
 * centre) is expected and is not an error: it is the reason the list is plural. The fallback target
 * is [Settings.ACTION_APPLICATION_DETAILS_SETTINGS], which exists on every Android, so the user
 * always ends up somewhere they can act.
 */
object PermissionSettingsLauncher {

    private const val TAG = "AlarmHub/Permissions"

    /** The MIUI security-centre package. Its pages are only reachable with a `<queries>` entry. */
    private const val MIUI_SECURITY = "com.miui.securitycenter"

    /**
     * Tries every candidate for [key], then the app detail page.
     *
     * @return true when a vendor or system settings page actually opened; false when everything was
     *   refused and the app detail page was used instead.
     */
    fun open(context: Context, key: PermissionKey): Boolean = openFirstOf(context, candidates(context, key))

    /**
     * The FR-5.3 sequence over an explicit list, so the fallback can be exercised on demand.
     *
     * Splitting it out is what makes 「兜底页可用」 checkable without a Xiaomi phone: passing an empty
     * list is exactly the situation "every MIUI candidate failed", and the only observable difference
     * should be which page appears.
     *
     * @return true **only** when one of [intents] opened. Opening the fallback still returns false:
     *   the caller has to be able to tell the user that the vendor page was not reached, and the
     *   first version of this function returned the fallback's own result — which made `opened` true
     *   and silently suppressed the FR-5.3 notice on exactly the phones that need it.
     */
    fun openFirstOf(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (tryStart(context, intent)) {
                Log.i(TAG, "opened ${describe(intent)}")
                return true
            }
        }
        Log.w(TAG, "no candidate could be opened; falling back to the app detail page")
        tryStart(context, appDetails(context))
        return false
    }

    /** The app's own system detail page — PRD FR-5.3's 兜底. */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri(context))

    /** The ordered candidates for one row. Exposed so the debug dump can print them. */
    fun candidates(context: Context, key: PermissionKey): List<Intent> {
        val pkg = packageUri(context)
        val miui = PermissionInspector.isMiui()
        return when (key) {
            PermissionKey.EXACT_ALARM -> listOfNotNull(
                // API 31+. On older releases there is nothing to grant, so this row is a no-op
                // there and the list falls straight through to the app detail page.
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(pkg),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkg),
            )

            PermissionKey.NOTIFICATIONS -> listOfNotNull(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                // Pre-API-26 spelling, still answered by some ROMs.
                Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                    .putExtra("app_package", context.packageName)
                    .putExtra("app_uid", context.applicationInfo.uid),
                appDetails(context),
            )

            PermissionKey.FULL_SCREEN_INTENT -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(pkg)
                } else {
                    null
                },
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                appDetails(context),
            )

            PermissionKey.BATTERY_UNRESTRICTED -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Asks for this one app directly; needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
                    add(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(pkg),
                    )
                }
                if (miui) addAll(miuiPowerCandidates())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                add(appDetails(context))
            }

            PermissionKey.BACKGROUND_POPUP -> buildList {
                if (miui) addAll(miuiPermissionEditorCandidates(context))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(pkg))
                }
                add(appDetails(context))
            }

            PermissionKey.AUTOSTART -> buildList {
                if (miui) addAll(miuiAutostartCandidates())
                add(appDetails(context))
            }

            PermissionKey.LOCK_SCREEN_DISPLAY -> buildList {
                if (miui) addAll(miuiPermissionEditorCandidates(context))
                add(appDetails(context))
            }
        }
    }

    // ---- MIUI candidates, most specific first ---------------------------------------------

    private fun miuiAutostartCandidates(): List<Intent> = listOf(
        // The page the user is told to look for. An explicit component, because MIUI does not
        // export a stable action for it.
        component(MIUI_SECURITY, "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        component(MIUI_SECURITY, "com.miui.permcenter.autostart.AutoStartDetailManagementActivity"),
    )

    private fun miuiPermissionEditorCandidates(context: Context): List<Intent> = listOf(
        // 「应用权限管理」——后台弹出界面 / 锁屏显示 的入口就在这一页的「其他权限」下面。
        //
        // Why not also offer `…permcenter.settings.OtherPermissionsActivity`, which is the page the
        // two switches actually live on? Verified against the real HyperOS 3 build
        // (`com.miui.securitycenter`, V816) with `aapt2 dump xmltree`: it is `exported="true"` but
        // also declares `android:permission="com.miui.securitycenter.permission.SYSTEM_PERMISSION_DECLARE"`,
        // a signature-level permission of the security centre that no third-party app can hold. Adding
        // it as a candidate would therefore only ever produce one more caught `SecurityException`, so
        // the honest thing is the one extra tap the user has to make — which is what PRD FR-5.3's
        // 图文指路 is for, and why the row's description says so.
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setComponent(ComponentName(MIUI_SECURITY, "com.miui.permcenter.permissions.PermissionsEditorActivity"))
            .putExtra("extra_pkgname", context.packageName)
            .putExtra("package_name", context.packageName),
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setComponent(
                ComponentName(MIUI_SECURITY, "com.miui.permcenter.permissions.AppPermissionsEditorActivity"),
            )
            .putExtra("extra_pkgname", context.packageName)
            .putExtra("package_name", context.packageName),
        component(MIUI_SECURITY, "com.miui.permcenter.privacymanager.SpecialPermissionActivity"),
    )

    private fun miuiPowerCandidates(): List<Intent> = listOf(
        component(MIUI_SECURITY, "com.miui.powercenter.PowerSettings"),
        component(MIUI_SECURITY, "com.miui.powercenter.BatteryDetailActivity"),
    )

    // ---- helpers --------------------------------------------------------------------------

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    /**
     * `FLAG_ACTIVITY_NEW_TASK` is set unconditionally: the same list is used from the plugin (which
     * holds the activity) and from diagnostics (which may not), and a settings page opened in its own
     * task behaves identically either way. It also keeps the alarm app's own task on the back stack,
     * so returning from Settings lands back on the page the user left.
     */
    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.d(TAG, "not present here: ${describe(intent)} (${e.message})")
        false
    } catch (e: SecurityException) {
        // A component that exists but is not exported to third-party apps. Treat it exactly like a
        // missing one: that is what the fallback is for.
        Log.d(TAG, "refused: ${describe(intent)} (${e.message})")
        false
    }

    private fun describe(intent: Intent): String =
        intent.component?.flattenToShortString() ?: intent.action ?: "?"
}
