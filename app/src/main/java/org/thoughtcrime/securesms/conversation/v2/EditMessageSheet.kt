package org.thoughtcrime.securesms.conversation.v2

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.databinding.FragmentEditMessageBinding
import org.session.libsession.messaging.messages.control.MessageEdit
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsignal.crypto.MnemonicCodec
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.crypto.MnemonicUtilities
import org.thoughtcrime.securesms.database.MessageEditDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.XsessionSurfaces
import org.thoughtcrime.securesms.util.animateXsessionEntrance
import javax.inject.Inject

@AndroidEntryPoint
class EditMessageSheet : BottomSheetDialogFragment(R.layout.fragment_edit_message) {
    @Inject lateinit var messages: MmsSmsDatabase
    @Inject lateinit var sender: MessageSender
    @Inject lateinit var clock: SnodeClock
    @Inject lateinit var loginState: LoginStateRepository
    @Inject lateinit var recipients: RecipientRepository
    @Inject lateinit var proStatus: ProStatusManager

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            dismissWithAnimation = true
            behavior.skipCollapsed = true
        }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentEditMessageBinding.bind(view)
        val surfaces = XsessionSurfaces(view.context)
        binding.editorSurface.background = surfaces.editor()
        binding.editorHandle.background = surfaces.handle()
        binding.editorIcon.background = surfaces.icon()
        binding.editorIcon.imageTintList = ColorStateList.valueOf(surfaces.iconColor())
        binding.editMessageText.background = surfaces.input()
        surfaces.primaryButton(binding.editMessageSave)
        binding.root.doOnPreDraw {
            animateXsessionEntrance(
                binding.editorHandle, binding.editorIcon, binding.editMessageTitle,
                binding.editMessageCancel, binding.editorLabel, binding.editMessageText,
                binding.editMessageSave,
            )
        }
        val args = requireArguments()
        val id = MessageId(args.getLong(ARG_ID), args.getBoolean(ARG_MMS))
        val threadId = args.getLong(ARG_THREAD)
        val address = Address.fromSerialized(requireNotNull(args.getString(ARG_ADDRESS)))
        val appContext = requireContext().applicationContext
        var original: MessageRecord? = null

        binding.editMessageSave.isEnabled = false
        binding.editMessageText.isEnabled = false
        binding.editMessageCancel.setOnClickListener { closeEditor(binding) }
        binding.editMessageText.doAfterTextChanged { value ->
            binding.editorError.isVisible = false
            val text = value?.toString()?.trim().orEmpty()
            binding.editMessageSave.isEnabled = text.isNotEmpty() && original?.body != text
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { messages.getMessageById(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unable to load the message for editing", e)
                null
            }
            if (loaded == null || loaded.threadId != threadId ||
                !loaded.isOutgoing || !MessageEditDatabase.canEditBody(loaded)
            ) {
                showError(binding, R.string.xsessionEditUnavailable)
                return@launch
            }
            original = loaded
            binding.editMessageText.apply {
                isEnabled = true
                setText(loaded.body)
                setSelection(length())
                requestFocus()
                post {
                    if (isAttachedToWindow && hasFocus()) {
                        dialog?.window?.let { window ->
                            WindowCompat.getInsetsController(window, this)
                                .show(WindowInsetsCompat.Type.ime())
                        }
                    }
                }
            }
        }

        binding.editMessageSave.setOnClickListener {
            val before = original ?: return@setOnClickListener
            val text = binding.editMessageText.text?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text == before.body) return@setOnClickListener

            setSaving(binding, true)
            viewLifecycleOwner.lifecycleScope.launch {
                val error = try {
                    withContext(Dispatchers.IO) {
                        val target = messages.getMessageById(id)
                        val recipient = recipients.getRecipientSync(address)
                        if (target == null || target.threadId != threadId || !target.isOutgoing ||
                            !MessageEditDatabase.canEditBody(target) || target.editedAt != before.editedAt ||
                            recipient.blocked || !MessageEditDatabase.supports(address)
                        ) return@withContext R.string.xsessionEditUnavailable

                        val maxChars = proStatus.getCharacterLimit(proStatus.currentUserHasProAccess())
                        if (text.codePointCount(0, text.length) > maxChars ||
                            text.toByteArray(Charsets.UTF_8).size > MessageEdit.MAX_BODY_BYTES
                        ) return@withContext R.string.xsessionEditTooLong

                        if (!recipient.isLocalNumber) {
                            val seed = loginState.peekLoginState()?.seeded?.seed?.data?.toHexString()
                            if (!seed.isNullOrBlank()) {
                                val phrase = MnemonicCodec { MnemonicUtilities.loadFileContents(appContext, it) }
                                    .encode(seed, MnemonicCodec.Language.Configuration.english)
                                if (phrase in text) return@withContext R.string.xsessionEditRecoveryPhrase
                            }
                        }

                        val edit = MessageEdit(target.dateSent, text).apply {
                            sentTimestamp = maxOf(clock.currentTimeMillis(), target.dateSent + 1, target.editedAt + 1)
                        }
                        proStatus.addProFeatures(edit)
                        sender.sendNonDurably(edit, address, isSyncMessage = recipient.isLocalNumber)
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    R.string.xsessionEditFailed
                }

                setSaving(binding, false)
                if (error == null) {
                    closeEditor(binding)
                } else {
                    showError(binding, error)
                }
            }
        }
    }

    private fun showError(binding: FragmentEditMessageBinding, error: Int) {
        binding.editorError.setText(error)
        binding.editorError.isVisible = true
        animateXsessionEntrance(binding.editorError)
    }

    private fun setSaving(binding: FragmentEditMessageBinding, saving: Boolean) {
        isCancelable = !saving
        (dialog as? BottomSheetDialog)?.behavior?.isDraggable = !saving
        binding.editMessageText.isEnabled = !saving
        binding.editMessageCancel.isEnabled = !saving
        binding.editMessageSave.isEnabled = !saving
        binding.editMessageSave.setText(if (saving) R.string.xsessionSavingEdit else R.string.save)
    }

    private fun closeEditor(binding: FragmentEditMessageBinding) {
        dialog?.window?.let {
            WindowCompat.getInsetsController(it, binding.root).hide(WindowInsetsCompat.Type.ime())
        }
        dismiss()
    }

    companion object {
        const val TAG = "EditMessage"
        private const val ARG_ID = "id"
        private const val ARG_MMS = "mms"
        private const val ARG_THREAD = "thread"
        private const val ARG_ADDRESS = "address"

        fun create(message: MessageRecord, address: Address) = EditMessageSheet().apply {
            arguments = bundleOf(
                ARG_ID to message.id, ARG_MMS to message.isMms,
                ARG_THREAD to message.threadId, ARG_ADDRESS to address.toString(),
            )
        }
    }
}
