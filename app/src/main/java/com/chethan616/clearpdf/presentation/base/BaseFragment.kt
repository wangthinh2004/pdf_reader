package com.chethan616.clearpdf.presentation.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.chethan616.clearpdf.presentation.common.UiText
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Owns a ViewBinding for the view's lifetime (cleared in onDestroyView to avoid leaking the
 * view hierarchy) and splits setup into [initViews], [initListeners] and [observeViewModel].
 */
abstract class BaseFragment<VB : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup?, Boolean) -> VB
) : Fragment() {

    private var _binding: VB? = null
    protected val binding: VB
        get() = checkNotNull(_binding) { "Binding used outside the fragment's view lifecycle" }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViews(savedInstanceState)
        initListeners()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    protected open fun initViews(savedInstanceState: Bundle?) {}

    protected open fun initListeners() {}

    protected open fun observeViewModel() {}

    /** Collects while the view is at least STARTED; stops in the background, restarts on return. */
    protected fun <T> Flow<T>.collectOnStarted(block: suspend (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { block(it) }
            }
        }
    }

    protected fun showMessage(text: UiText) {
        Snackbar.make(binding.root, text.resolve(requireContext()), Snackbar.LENGTH_LONG).show()
    }
}
