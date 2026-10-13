package com.chethan616.clearpdf.presentation.base

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.viewbinding.ViewBinding
import com.chethan616.clearpdf.databinding.FragmentToolBinding
import com.chethan616.clearpdf.databinding.ViewToolStatusBinding
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Base for every tool screen: a back + title bar over a scrolling column. Subclasses pass their
 * body layout's inflate function and bind it through [body].
 */
abstract class BaseToolFragment<B : ViewBinding>(
    private val inflateBody: (LayoutInflater, ViewGroup?, Boolean) -> B
) : BaseFragment<FragmentToolBinding>(FragmentToolBinding::inflate) {

    private var _body: B? = null
    protected val body: B
        get() = checkNotNull(_body) { "Body used outside the fragment's view lifecycle" }

    @get:StringRes
    protected abstract val titleRes: Int

    private var statusJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        _body = inflateBody(inflater, binding.content, false)
        if (body.root.parent == null) binding.content.addView(body.root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applySystemBarPadding(top = true)
        binding.scroll.applySystemBarPadding(bottom = true, imeBottom = true)
        binding.toolbar.setTitle(titleRes)
        binding.toolbar.setNavigationOnClickListener { onBackClicked() }
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroyView() {
        _body = null
        super.onDestroyView()
    }

    /** Back arrow in the bar. Screens with unsaved work override this to confirm first. */
    protected open fun onBackClicked() {
        findNavController().popBackStack()
    }

    /** Shows a success/error card that hides itself after 3 s or when dismissed. */
    protected fun showStatus(status: ViewToolStatusBinding, text: UiText, isError: Boolean) {
        statusJob?.cancel()
        val hide = { ToolUi.status(status, null, isError = false) }
        ToolUi.status(status, text.resolve(requireContext()), isError, onDismiss = hide)
        statusJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(STATUS_VISIBLE_MS)
            hide()
        }
    }

    /** Opens a tool's output in the PDF viewer. */
    protected fun viewOutput(uri: Uri) = AppNavigator.openPdfViewer(findNavController(), uri)

    private companion object {
        const val STATUS_VISIBLE_MS = 3_000L
    }
}
