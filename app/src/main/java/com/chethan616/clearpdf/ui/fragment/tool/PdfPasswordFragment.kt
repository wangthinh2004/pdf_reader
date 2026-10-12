package com.chethan616.clearpdf.ui.fragment.tool

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ToolPasswordBinding
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.color
import com.kyant.pdfcore.security.PdfSecurityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encrypt or decrypt a PDF with a password. The two screens differ only in strings, the confirm
 * field and the [PdfSecurityService] call, so they share this fragment.
 */
abstract class PdfPasswordFragment : ToolFragment<ToolPasswordBinding>() {

    protected abstract val encrypt: Boolean

    private var sourceUri: Uri? = null
    private var outputUri: Uri? = null
    private var busy = false

    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        sourceUri = uri
        outputUri = null
        body.password.setText("")
        body.confirm.setText("")
        showMessage(null, false)
        render()
    }

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
        val source = sourceUri ?: return@registerForActivityResult
        if (destination == null) return@registerForActivityResult
        val password = body.password.text?.toString().orEmpty()
        val ctx = requireContext().applicationContext
        busy = true
        showMessage(null, false)
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (encrypt) PdfSecurityService.encryptToUri(ctx, source, destination, password)
                    else PdfSecurityService.decryptToUri(ctx, source, destination, password)
                }
                outputUri = destination
                showMessage(getString(if (encrypt) R.string.encrypt_pdf_success else R.string.decrypt_pdf_success), false)
            } catch (_: PdfSecurityService.PasswordRequiredException) {
                showMessage(getString(if (encrypt) R.string.encrypt_pdf_source_protected else R.string.decrypt_pdf_invalid), true)
            } catch (_: Exception) {
                showMessage(getString(R.string.file_preview_unsupported), true)
            } finally {
                busy = false
                if (isBodyAlive()) render()
            }
        }
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolPasswordBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            sourceUri = BundleCompat.getParcelable(savedInstanceState, KEY_SOURCE, Uri::class.java)
            outputUri = BundleCompat.getParcelable(savedInstanceState, KEY_OUTPUT, Uri::class.java)
        }
        val accent = accent()
        ToolUi.hero(
            body.hero,
            if (encrypt) R.drawable.ic_lock else R.drawable.ic_lock_open,
            accent,
            getString(if (encrypt) R.string.encrypt_pdf_title else R.string.decrypt_pdf_title),
            getString(if (encrypt) R.string.encrypt_pdf_subtitle else R.string.decrypt_pdf_subtitle),
            getString(if (encrypt) R.string.encrypt_pdf_select else R.string.decrypt_pdf_select)
        ) { openLauncher.launch(arrayOf("application/pdf")) }
        body.passwordLayout.hint = getString(if (encrypt) R.string.encrypt_pdf_password else R.string.decrypt_pdf_password)
        body.confirmLayout.hint = getString(R.string.encrypt_pdf_confirm)
        body.confirmLayout.isVisible = encrypt
        body.viewSource.setOnClickListener { sourceUri?.let(::viewOutput) }
        ToolUi.primaryButton(body.run, accent)
        body.run.setOnClickListener { onRun() }
        body.openOutput.root.setText(if (encrypt) R.string.view_encrypted_pdf else R.string.view_unlocked_pdf)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_SOURCE, sourceUri)
        outState.putParcelable(KEY_OUTPUT, outputUri)
    }

    private fun accent() = requireContext().color(if (encrypt) R.color.tool_encrypt else R.color.tool_decrypt)

    private fun onRun() {
        if (busy) return
        val password = body.password.text?.toString().orEmpty()
        if (encrypt) {
            when {
                password.isBlank() -> return showMessage(getString(R.string.encrypt_pdf_password_required), true)
                password != body.confirm.text?.toString().orEmpty() -> return showMessage(getString(R.string.encrypt_pdf_mismatch), true)
            }
        }
        saveLauncher.launch(if (encrypt) "encrypted.pdf" else "unlocked.pdf")
    }

    private fun render() {
        val source = sourceUri
        body.options.isVisible = source != null
        body.sourceName.text = source?.let { AppNavigator.queryDisplayName(requireContext(), it) }
        ToolUi.setBusy(body.run, busy, null, getString(if (encrypt) R.string.encrypt_pdf_output else R.string.decrypt_pdf_output))
        ToolUi.openOutput(body.openOutput.root, outputUri, requireContext().color(R.color.app_success), ::viewOutput)
    }

    private fun showMessage(message: String?, isError: Boolean) {
        if (!isBodyAlive()) return
        ToolUi.status(body.status, message, isError)
    }

    private fun isBodyAlive() = view != null

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_OUTPUT = "output"
    }
}

class EncryptPdfFragment : PdfPasswordFragment() {
    override val titleRes = R.string.encrypt_pdf_title
    override val encrypt = true
}

class DecryptPdfFragment : PdfPasswordFragment() {
    override val titleRes = R.string.decrypt_pdf_title
    override val encrypt = false
}
