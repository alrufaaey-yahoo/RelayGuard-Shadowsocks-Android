package com.github.shadowsocks

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.security.KeyChain
import android.util.Base64
import com.therealaleph.mhrv.Native
import java.io.File

internal object RelayCaInstaller {
    fun install(context: Context): Boolean {
        val relayDir = File(context.filesDir, "relayguard").apply { mkdirs() }
        Native.setDataDir(relayDir.absolutePath)
        val certFile = File(context.filesDir, "relayguard-ca.crt")
        if (!Native.exportCa(certFile.absolutePath) || !certFile.isFile || certFile.length() == 0L) return false
        val pem = certFile.readText(Charsets.US_ASCII)
        val body = pem.substringAfter("-----BEGIN CERTIFICATE-----", "")
            .substringBefore("-----END CERTIFICATE-----", "")
            .replace(Regex("\\s+"), "")
        if (body.isBlank()) return false
        val der = try { Base64.decode(body, Base64.DEFAULT) } catch (_: IllegalArgumentException) { return false }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!saveToDownloads(context, certFile.readBytes())) return false
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } else {
            val intent = KeyChain.createInstallIntent().apply {
                putExtra(KeyChain.EXTRA_CERTIFICATE, der)
                putExtra(KeyChain.EXTRA_NAME, "RelayGuard HTTPS Relay CA")
            }
            context.startActivity(intent)
        }
        return true
    }

    private fun saveToDownloads(context: Context, pem: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val name = "relayguard-ca.crt"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            try {
                resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { it.write(pem) } ?: return false
                return true
            } catch (_: Throwable) {
                return false
            }
        }
        return try {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
            directory.mkdirs()
            File(directory, "relayguard-ca.crt").writeBytes(pem)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
