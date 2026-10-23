package com.github.shadowsocks

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.widget.MainListListener
import androidx.core.view.ViewCompat

class ScriptRelayPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.privateStore
        addPreferencesFromResource(R.xml.pref_script_relay)

        findPreference<EditTextPreference>(Key.relayDeploymentUrls)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            it.minLines = 2
        }
        findPreference<EditTextPreference>(Key.relayAuthKey)?.apply {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                if (it.text.isNullOrBlank()) getString(R.string.relay_auth_key_missing) else getString(R.string.relay_auth_key_set)
            }
        }
        findPreference<EditTextPreference>(Key.relayFrontDomain)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        findPreference<Preference>("relayInstallCa")?.setOnPreferenceClickListener {
            try {
                if (RelayCaInstaller.install(requireContext())) {
                    Toast.makeText(requireContext(), R.string.relay_ca_install_opened, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), R.string.relay_ca_install_failed, Toast.LENGTH_LONG).show()
                }
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
                    false
                } else true
            }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(listView, MainListListener)
    }
}
