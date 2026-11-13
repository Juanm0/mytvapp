package com.quilmes.xuper.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Recibe el resultado de la instalación que inició [AppUpdater]. Cuando Android necesita que la persona
 * confirme (STATUS_PENDING_USER_ACTION) abre su pantalla "¿Querés instalar esta actualización?".
 * Registrado en el manifest (no exportado): solo el sistema lo dispara con el PendingIntent de la sesión.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("XuperUpdate", "Estado de la instalación: $status ($message)")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm == null) {
                AppUpdater.onInstallResult(PackageInstaller.STATUS_FAILURE, "Android no mostró la pantalla de confirmación")
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(confirm)
            } catch (e: Exception) {
                Log.w("XuperUpdate", "No se pudo abrir la confirmación del sistema", e)
                AppUpdater.onInstallResult(PackageInstaller.STATUS_FAILURE, "No se pudo abrir la confirmación de Android")
            }
            return
        }
        AppUpdater.onInstallResult(status, message)
    }
}
