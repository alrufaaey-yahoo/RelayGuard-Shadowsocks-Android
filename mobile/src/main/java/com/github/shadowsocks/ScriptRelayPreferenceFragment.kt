package com.github.shadowsocks

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.widget.MainListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ScriptRelayPreferenceFragment : PreferenceFragmentCompat() {
    private data class RelayConfig(val deploymentUrl: String, val authKey: String, val frontDomain: String)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.privateStore
        addPreferencesFromResource(R.xml.pref_script_relay)

        findPreference<EditTextPreference>(Key.relayConfigUrl)?.apply {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                it.text?.takeIf(String::isNotBlank) ?: getString(R.string.relay_remote_config_url_summary)
            }
        }
        findPreference<Preference>(Key.relayRefreshConfig)?.setOnPreferenceClickListener { preference ->
            refreshConfig(preference)
            true
        }
        findPreference<Preference>("relayInstallCa")?.setOnPreferenceClickListener {
            try {
                val opened = RelayCaInstaller.install(requireContext())
                Toast.makeText(requireContext(), if (opened) R.string.relay_ca_install_opened else R.string.relay_ca_install_failed,
                    Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
                Toast.makeText(requireContext(), R.string.relay_ca_install_failed, Toast.LENGTH_LONG).show()
            }
            true
        }
        listOf(Key.relayCoalesceStepMs, Key.relayCoalesceMaxMs).forEach { key ->
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
            }
        }
        findPreference<SwitchPreference>(Key.relayEnabled)?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue == true && DataStore.serviceMode != Key.modeVpn) {
                    Toast.makeText(requireContext(), R.string.relay_vpn_mode_only, Toast.LENGTH_LONG).show()
                    return@OnPreferenceChangeListener false
                }
                if (newValue == true && (DataStore.relayDeploymentUrls.isBlank() || DataStore.relayAuthKey.isBlank())) {
                    Toast.makeText(requireContext(), R.string.relay_missing_credentials, Toast.LENGTH_LONG).show()
                    return@OnPreferenceChangeListener false
                }
                if ((activity as? MainActivity)?.state?.canStop == true) {
                    Toast.makeText(requireContext(), R.string.relay_stop_before_change, Toast.LENGTH_LONG).show()
                    return@OnPreferenceChangeListener false
                }
                true
            }
    }

    private fun refreshConfig(preference: Preference) {
        val configUrl = DataStore.relayConfigUrl
        if (configUrl.isBlank()) {
            Toast.makeText(requireContext(), R.string.relay_remote_config_missing, Toast.LENGTH_LONG).show()
            return
        }
        preference.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { downloadConfig(configUrl) } }
            preference.isEnabled = true
            result.onSuccess { config ->
                DataStore.privateStore.putString(Key.relayDeploymentUrls, config.deploymentUrl)
                DataStore.privateStore.putString(Key.relayAuthKey, config.authKey)
                DataStore.privateStore.putString(Key.relayFrontDomain, config.frontDomain)
                preference.summary = getString(R.string.relay_remote_config_loaded)
                Toast.makeText(requireContext(), R.string.relay_fetch_success, Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(requireContext(), R.string.relay_fetch_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadConfig(rawUrl: String): RelayConfig {
        val url = URL(rawUrl.trim())
        require(url.protocol.equals("https", ignoreCase = true))
        val connection = (url.openConnection() as? HttpsURLConnection)
            ?: throw IllegalArgumentException("HTTPS required")
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == HttpsURLConnection.HTTP_OK)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 32 * 1024) { "Remote config is too large" }
                    output.write(buffer, 0, count)
                }
            }
            val values = mutableMapOf<String, String>()
            output.toString(Charsets.UTF_8.name()).lineSequence().forEach { line ->
                val clean = line.substringBefore('#').trim()
                val separator = clean.indexOf('=')
                if (separator <= 0) return@forEach
                val key = clean.substring(0, separator).trim().uppercase()
                if (key in setOf("URL", "KEY", "FRONT")) values[key] = clean.substring(separator + 1).trim().trim('"', '\'')
            }
            val deployment = values["URL"].orEmpty()
            val authKey = values["KEY"].orEmpty()
            val front = values["FRONT"].orEmpty().trimEnd('.')
            require(isValidDeployment(deployment) && authKey.isNotBlank() && isValidHost(front))
            return RelayConfig(deployment, authKey, front)
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidDeployment(value: String): Boolean {
        val parts = value.split(Regex("[,;\\s]+")).filter(String::isNotBlank)
        if (parts.isEmpty()) return false
        val idPattern = Regex("^[A-Za-z0-9_-]+$")
        val urlPattern = Regex("(?i)^https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec/?(?:\\?.*)?$")
        return parts.all { idPattern.matches(it) || urlPattern.matches(it) }
    }

    private fun isValidHost(value: String): Boolean = try {
        value.isNotBlank() && value.length <= 253 &&
            URI("https://$value").let { it.host.equals(value, ignoreCase = true) && it.rawUserInfo == null && it.port == -1 }
    } catch (_: Exception) {
        false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(listView, MainListListener)
    }
}
