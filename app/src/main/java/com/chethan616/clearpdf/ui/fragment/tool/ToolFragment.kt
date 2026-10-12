package com.chethan616.clearpdf.ui.fragment.tool

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewbinding.ViewBinding
import com.chethan616.clearpdf.databinding.FragmentToolBinding
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.applySystemBarPadding

/**
 * Base for every tool screen: a back + title bar over a scrolling column. Subclasses inflate their
 * own body layout ([B]) into that column and bind it in [onBodyCreated].
 */
abstract class ToolFragment<B : ViewBinding> : Fragment() {

    private var _frame: FragmentToolBinding? = null
    protected val frame: FragmentToolBinding get() = _frame!!

    private var _body: B? = null
    protected val body: B get() = _body!!

    @get:StringRes
    protected abstract val titleRes: Int

    protected abstract fun inflateBody(inflater: LayoutInflater, parent: ViewGroup): B

    protected abstract fun onBodyCreated(savedInstanceState: Bundle?)

    final override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _frame = FragmentToolBinding.inflate(inflater, container, false)
        _body = inflateBody(inflater, frame.content)
        if (_body!!.root.parent == null) frame.content.addView(_body!!.root)
        return frame.root
    }

    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        frame.root.applySystemBarPadding(top = true)
        frame.scroll.applySystemBarPadding(bottom = true, imeBottom = true)
        frame.toolbar.setTitle(titleRes)
        frame.toolbar.setNavigationOnClickListener { onBackClicked() }
        onBodyCreated(savedInstanceState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _body = null
        _frame = null
    }

    /** Back arrow in the bar. Screens with unsaved work override this to confirm first. */
    protected open fun onBackClicked() {
        findNavController().popBackStack()
    }

    /** Opens a tool's output in the PDF viewer. */
    protected fun viewOutput(uri: Uri) = AppNavigator.openPdfViewer(findNavController(), uri)
}

/** A [androidx.lifecycle.ViewModelProvider.Factory] for view models that take constructor arguments. */
inline fun <reified VM : androidx.lifecycle.ViewModel> viewModelFactory(crossinline create: () -> VM) =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = create() as T
    }
