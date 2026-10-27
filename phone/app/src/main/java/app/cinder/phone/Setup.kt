package app.cinder.phone

import android.Manifest
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle

/**
 * QR setup. After a reset, tapping the welcome screen 6+ times opens a QR
 * scanner. Scanning a Cinder setup code makes Android download this app,
 * make it the phone's manager, and pass along the settings in the code.
 */
object Setup {
    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isManaged(ctx: Context) =
        ctx.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(ctx.packageName)

    /** Runs once setup finishes: settings saved, permissions granted, service started. */
    fun finish(ctx: Context, extras: PersistableBundle?) {
        val prefs = Prefs(ctx)
        prefs.saveExtras(extras)
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isDeviceOwnerApp(ctx.packageName)) {
            if (Build.VERSION.SDK_INT >= 33) {
                dpm.setPermissionGrantState(admin(ctx), ctx.packageName, Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
        CinderService.start(ctx)
    }

    /** Hands the phone back: Cinder stops managing it and can be uninstalled. */
    fun release(ctx: Context) {
        CinderService.stop(ctx)
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        @Suppress("DEPRECATION")
        if (dpm.isDeviceOwnerApp(ctx.packageName)) dpm.clearDeviceOwnerApp(ctx.packageName)
    }

    @Suppress("DEPRECATION")
    fun extrasFrom(intent: Intent?): PersistableBundle? =
        intent?.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
}

/** Android 10+: "what kind of setup?" Always a fully managed phone. */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs(this).saveExtras(Setup.extrasFrom(intent))
        setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
        finish()
    }
}

/** Android 10+: setup is done, apply settings and start. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Setup.finish(this, Setup.extrasFrom(intent))
        setResult(RESULT_OK)
        finish()
    }
}

class AdminReceiver : DeviceAdminReceiver() {
    /** Android 8 and 9 finish setup here instead. */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Setup.finish(context, Setup.extrasFrom(intent))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CinderService.start(context)
    }
}
