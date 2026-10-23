/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.util.LongSparseArray
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.plugin.PluginConfiguration
import com.github.shadowsocks.plugin.fragment.showAllowingStateLoss
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Action
import com.github.shadowsocks.utils.OpenJson
import com.github.shadowsocks.utils.SaveJson
import com.github.shadowsocks.utils.readableMessage
import com.github.shadowsocks.widget.ListHolderListener
import com.github.shadowsocks.widget.MainListListener
import com.github.shadowsocks.widget.UndoSnackbarManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.charset.StandardCharsets

class ProfilesFragment : ToolbarFragment(), Toolbar.OnMenuItemClickListener {
    companion object {
        /**
         * used for callback from stateChanged from MainActivity
         */
        var instance: ProfilesFragment? = null

        private const val KEY_URL = "com.github.shadowsocks.QRCodeDialog.KEY_URL"

        private val iso88591 = StandardCharsets.ISO_8859_1.newEncoder()
    }

    /**
     * Is ProfilesFragment editable at all.
     */
    private val isEnabled get() = (activity as MainActivity).state.let { it.canStop || it == BaseService.State.Stopped }

    private fun isProfileEditable(id: Long) =
            (activity as MainActivity).state == BaseService.State.Stopped || id !in Core.activeProfileIds

    class QRCodeDialog() : DialogFragment() {
        constructor(url: String) : this() {
            arguments = bundleOf(Pair(KEY_URL, url))
        }

        /**
         * Based on:
         * https://android.googlesource.com/platform/packages/apps/Settings/+/0d706f0/src/com/android/settings/wifi/qrcode/QrCodeGenerator.java
         * https://android.googlesource.com/platform/packages/apps/Settings/+/8a9ccfd/src/com/android/settings/wifi/dpp/WifiDppQrCodeGeneratorFragment.java#153
         */
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = try {
            val url = arguments?.getString(KEY_URL)!!
            val size = resources.getDimensionPixelSize(R.dimen.qrcode_size)
            val hints = mutableMapOf<EncodeHintType, Any>()
            if (!iso88591.canEncode(url)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
            val qrBits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
            ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(size, size)
                setImageBitmap(Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
                    for (x in 0 until size) for (y in 0 until size) {
                        setPixel(x, y, if (qrBits.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                })
            }
        } catch (e: WriterException) {
            Timber.w(e)
            (activity as MainActivity).snackbar().setText(e.readableMessage).show()
            dismiss()
            null
        }
    }

    inner class ProfileViewHolder(view: View) : RecyclerView.ViewHolder(view),
            View.OnClickListener, PopupMenu.OnMenuItemClickListener {
        internal lateinit var item: Profile

        private val text1 = itemView.findViewById<TextView>(android.R.id.text1)
        private val text2 = itemView.findViewById<TextView>(android.R.id.text2)
        private val traffic = itemView.findViewById<TextView>(R.id.traffic)
        private val edit = itemView.findViewById<View>(R.id.edit)
        private val subscription = itemView.findViewById<View>(R.id.subscription)

        init {
            edit.visibility = View.GONE
            subscription.visibility = View.GONE
            itemView.setOnClickListener(this)
            val share = itemView.findViewById<View>(R.id.share)
            share.visibility = View.GONE
        }

        fun bind(item: Profile) {
            this.item = item
            val editable = isProfileEditable(item.id)
            edit.isEnabled = editable
            edit.alpha = if (editable) 1F else .5F
            subscription.isEnabled = editable
            subscription.alpha = if (editable) 1F else .5F
            var tx = item.tx
            var rx = item.rx
            statsCache[item.id]?.apply {
                tx += txTotal
                rx += rxTotal
            }
            text1.text = item.name?.takeIf(String::isNotBlank) ?: getString(R.string.profile_unlabelled)
            text2.text = null
            val context = requireContext()
            traffic.text = if (tx <= 0 && rx <= 0) null else getString(R.string.traffic,
                    Formatter.formatFileSize(context, tx), Formatter.formatFileSize(context, rx))

            if (item.id == DataStore.profileId) {
                itemView.isSelected = true
                selectedItem = this
            } else {
                itemView.isSelected = false
                if (selectedItem === this) selectedItem = null
            }

        }

        override fun onClick(v: View?) {
            if (isEnabled) {
                val activity = activity as MainActivity
                val old = DataStore.profileId
                Core.switchProfile(item.id)
                profilesAdapter.refreshId(old)
                itemView.isSelected = true
                if (activity.state.canStop) Core.reloadService()
            }
        }

        override fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
            R.id.action_qr_code -> {
                QRCodeDialog(this.item.toString()).showAllowingStateLoss(parentFragmentManager)
                true
            }
            R.id.action_export_clipboard -> {
                val success = Core.trySetPrimaryClip(this.item.toString(), true)
                (activity as MainActivity).snackbar().setText(
                        if (success) R.string.action_export_msg else R.string.action_export_err).show()
                true
            }
            else -> false
        }
    }

    inner class ProfilesAdapter : RecyclerView.Adapter<ProfileViewHolder>(), ProfileManager.Listener {
        internal val profiles = ProfileManager.getActiveProfiles()?.toMutableList() ?: mutableListOf()
        private val updated = HashSet<Profile>()

        init {
            setHasStableIds(true)   // see: http://stackoverflow.com/a/32488059/2245107
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) = holder.bind(profiles[position])
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder = ProfileViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.layout_profile, parent, false))

        override fun getItemCount(): Int = profiles.size
        override fun getItemId(position: Int): Long = profiles[position].id

        override fun onAdd(profile: Profile) {
            undoManager.flush()
            val pos = itemCount
            profiles += profile
            notifyItemInserted(pos)
        }

        fun filter(name: String) {
            val active = ProfileManager.getActiveProfiles()?.toMutableList() ?: mutableListOf()
            profiles.clear()
            val locale = resources.configuration.locale
            val lower = name.lowercase(locale)
            profiles.addAll(active.filter {
                it.name?.lowercase(locale)?.contains(lower) == true || it.host.lowercase(locale).contains(lower)
            })
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            undoManager.flush()
            val first = profiles[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(-1, from downTo to + 1)
            for (i in range) {
                val next = profiles[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                profiles[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            profiles[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            updated.forEach { ProfileManager.updateProfile(it) }
            updated.clear()
        }

        fun remove(pos: Int) {
            profiles.removeAt(pos)
            notifyItemRemoved(pos)
        }

        fun undo(actions: List<Pair<Int, Profile>>) {
            for ((index, item) in actions) {
                profiles.add(index, item)
                notifyItemInserted(index)
            }
        }

        fun commit(actions: List<Pair<Int, Profile>>) {
            for ((_, item) in actions) ProfileManager.delProfile(item.id)
        }

        fun refreshId(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }

        fun deepRefreshId(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index < 0) return
            profiles[index] = ProfileManager.getProfile(id)!!
            notifyItemChanged(index)
        }

        override fun onRemove(profileId: Long) {
            val index = profiles.indexOfFirst { it.id == profileId }
            if (index < 0) return
            profiles.removeAt(index)
            notifyItemRemoved(index)
            if (profileId == DataStore.profileId) DataStore.profileId = 0   // switch to null profile
        }

        override fun onCleared() {
            profiles.clear()
            notifyDataSetChanged()
        }

        override fun reloadProfiles() {
            profiles.clear()
            ProfileManager.getActiveProfiles()?.let { profiles.addAll(it) }
            notifyDataSetChanged()
        }
    }

    private var selectedItem: ProfileViewHolder? = null

    val profilesAdapter by lazy { ProfilesAdapter() }
    private lateinit var profilesList: RecyclerView
    private lateinit var emptyState: View
    private val layoutManager by lazy { LinearLayoutManager(context, RecyclerView.VERTICAL, false) }
    private lateinit var undoManager: UndoSnackbarManager<Profile>
    private val statsCache = LongSparseArray<TrafficStats>()

    private fun startConfig(profile: Profile) {
        profile.serialize()
        startActivity(Intent(context, ProfileConfigActivity::class.java).putExtra(Action.EXTRA_PROFILE_ID, profile.id))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.layout_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListHolderListener)
        toolbar.setTitle(R.string.profiles)
        toolbar.inflateMenu(R.menu.profile_manager_menu)
        toolbar.setOnMenuItemClickListener(this)

        profilesList = view.findViewById(R.id.list)
        emptyState = view.findViewById(R.id.empty_state)
        ViewCompat.setOnApplyWindowInsetsListener(profilesList, MainListListener)
        profilesList.layoutManager = layoutManager
        profilesList.addItemDecoration(DividerItemDecoration(context, layoutManager.orientation))
        profilesAdapter.profiles.indexOfFirst { it.id == DataStore.profileId }
            .takeIf { it >= 0 }?.let(layoutManager::scrollToPosition)
        val animator = DefaultItemAnimator()
        animator.supportsChangeAnimations = false // prevent fading-in/out when rebinding
        profilesList.itemAnimator = animator
        profilesList.adapter = profilesAdapter
        profilesAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateEmptyState()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateEmptyState()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateEmptyState()
        })
        updateEmptyState()
        instance = this
        ProfileManager.listener = profilesAdapter
        undoManager = UndoSnackbarManager(activity as MainActivity, profilesAdapter::undo, profilesAdapter::commit)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                ItemTouchHelper.START) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                    if (isProfileEditable((viewHolder as ProfileViewHolder).item.id)) {
                        super.getSwipeDirs(recyclerView, viewHolder)
                    } else 0

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                    if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                profilesAdapter.remove(index)
                undoManager.remove(Pair(index, (viewHolder as ProfileViewHolder).item))
            }

            override fun onMove(recyclerView: RecyclerView,
                                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                profilesAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                profilesAdapter.commitMove()
            }
        }).attachToRecyclerView(profilesList)
    }

    private fun updateEmptyState() {
        if (this::emptyState.isInitialized) {
            emptyState.visibility = if (profilesAdapter.itemCount == 0) View.VISIBLE else View.GONE
        }
    }

    private fun importFromClipboard() {
        val payload = try { Core.clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty() }
        catch (_: Exception) { "" }
        if (payload.isBlank()) {
            showImportError()
        } else if (ProfileClipboardCodec.isProtected(payload)) {
            val password = EditText(requireContext()).apply {
                hint = getString(R.string.profile_password)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_protected_profile)
                .setView(password)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val secret = password.text.toString()
                    viewLifecycleOwner.lifecycleScope.launch {
                        val decoded = runCatching {
                            withContext(Dispatchers.IO) { ProfileClipboardCodec.unprotect(payload, secret) }
                        }
                        decoded.onSuccess(::importPlainProfiles).onFailure { showImportError() }
                    }
                }.show()
        } else importPlainProfiles(payload)
    }

    private fun importPlainProfiles(payload: String) {
        val parsed = try {
            Profile.findAllUrls(payload, Core.currentProfile?.main).toList()
        } catch (_: Exception) { emptyList() }
        if (parsed.isEmpty()) {
            showImportError()
            return
        }
        val needsSelection = Core.currentProfile == null
        parsed.forEachIndexed { index, profile ->
            if (profile.name.isNullOrBlank()) profile.name = getString(R.string.profile_imported_name, index + 1)
            val created = ProfileManager.createProfile(profile)
            if (needsSelection && index == 0) DataStore.profileId = created.id
        }
        (activity as MainActivity).snackbar().setText(R.string.action_import_msg).show()
    }

    private fun showImportError() {
        (activity as? MainActivity)?.snackbar()?.setText(R.string.action_import_err)?.show()
    }

    private fun showExportDialog() {
        val profile = Core.currentProfile?.main
        if (profile == null) {
            (activity as MainActivity).snackbar().setText(R.string.profiles_required).show()
            return
        }
        val name = EditText(requireContext()).apply {
            hint = getString(R.string.profile_export_name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setText(profile.name?.takeIf(String::isNotBlank) ?: getString(R.string.profile_imported_name, 1))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_profile_title)
            .setMessage(R.string.export_profile_message)
            .setView(name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.export_without_password) { _, _ ->
                val result = ProfileClipboardCodec.exportPlain(profile, name.text.toString())
                val copied = Core.trySetPrimaryClip(result, true)
                (activity as MainActivity).snackbar().setText(
                    if (copied) R.string.action_export_msg else R.string.action_export_err).show()
            }
            .setNeutralButton(R.string.export_with_password) { _, _ ->
                askExportPassword(profile, name.text.toString())
            }
            .show()
    }

    private fun askExportPassword(profile: Profile, displayName: String) {
        val password = EditText(requireContext()).apply {
            hint = getString(R.string.profile_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val confirm = EditText(requireContext()).apply {
            hint = getString(R.string.profile_password_confirm)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val fields = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(password)
            addView(confirm)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_with_password)
            .setMessage(R.string.profile_password_hint)
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_export) { _, _ ->
                val secret = password.text.toString()
                if (secret.length < 8 || secret != confirm.text.toString()) {
                    (activity as MainActivity).snackbar().setText(
                        if (secret.length < 8) R.string.profile_password_too_short else R.string.profile_password_mismatch).show()
                    return@setPositiveButton
                }
                val plain = ProfileClipboardCodec.exportPlain(profile, displayName)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { ProfileClipboardCodec.protect(plain, secret) } }
                    result.onSuccess { protected ->
                        val copied = Core.trySetPrimaryClip(protected, true)
                        (activity as MainActivity).snackbar().setText(
                            if (copied) R.string.action_export_msg else R.string.action_export_err).show()
                    }.onFailure {
                        (activity as MainActivity).snackbar().setText(R.string.action_export_err).show()
                    }
                }
            }.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
                true
            }
            R.id.action_import_clipboard -> {
                importFromClipboard()
                true
            }
            R.id.action_import_file -> {
                startFilesForResult(importProfiles)
                true
            }
            R.id.action_replace_file -> {
                startFilesForResult(replaceProfiles)
                true
            }
            R.id.action_manual_settings -> {
                startConfig(ProfileManager.createProfile(
                        Profile().also { Core.currentProfile?.main?.copyFeatureSettingsTo(it) }))
                true
            }
            R.id.action_export_clipboard -> {
                showExportDialog()
                true
            }
            R.id.action_export_file -> {
                startFilesForResult(exportProfiles)
                true
            }
            else -> false
        }
    }

    private fun startFilesForResult(launcher: ActivityResultLauncher<String>) {
        try {
            return launcher.launch("")
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        (activity as MainActivity).snackbar(getString(R.string.file_manager_missing)).show()
    }

    private fun importOrReplaceProfiles(dataUris: List<Uri>, replace: Boolean = false) {
        if (dataUris.isEmpty()) return
        val activity = activity as MainActivity
        try {
            ProfileManager.createProfilesFromJson(dataUris.asSequence().map {
                activity.contentResolver.openInputStream(it)
            }.filterNotNull(), replace)
        } catch (e: Exception) {
            activity.snackbar(e.readableMessage).show()
        }
    }
    private val importProfiles = registerForActivityResult(OpenJson) { importOrReplaceProfiles(it) }
    private val replaceProfiles = registerForActivityResult(OpenJson) { importOrReplaceProfiles(it, true) }
    private val exportProfiles = registerForActivityResult(SaveJson) { data ->
        if (data != null) ProfileManager.serializeToJson()?.let { profiles ->
            val activity = activity as MainActivity
            try {
                activity.contentResolver.openOutputStream(data)!!.bufferedWriter().use {
                    it.write(profiles.toString(2))
                }
            } catch (e: Exception) {
                Timber.w(e)
                activity.snackbar(e.readableMessage).show()
            }
        }
    }

    fun onTrafficUpdated(profileId: Long, stats: TrafficStats) {
        if (profileId != 0L) {  // ignore aggregate stats
            statsCache.put(profileId, stats)
            profilesAdapter.refreshId(profileId)
        }
    }

    fun onTrafficPersisted(profileId: Long) {
        statsCache.remove(profileId)
        profilesAdapter.deepRefreshId(profileId)
    }

    override fun onDestroyView() {
        undoManager.flush()
        super.onDestroyView()
    }

    override fun onDestroy() {
        instance = null
        ProfileManager.listener = null
        super.onDestroy()
    }
}
