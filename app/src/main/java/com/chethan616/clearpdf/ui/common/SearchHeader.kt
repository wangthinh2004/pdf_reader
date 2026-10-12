package com.chethan616.clearpdf.ui.common

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.chethan616.clearpdf.databinding.ViewSearchHeaderBinding

/**
 * Drives `view_search_header.xml`: the search button swaps the title for a text field, the close
 * button clears the query and swaps back. [onQueryChange] fires on every edit.
 */
class SearchHeader(
    private val binding: ViewSearchHeaderBinding,
    title: CharSequence,
    hint: CharSequence,
    private val onQueryChange: (String) -> Unit,
    private val onActiveChange: (Boolean) -> Unit = {}
) {
    var active: Boolean = false
        private set

    init {
        binding.title.text = title
        binding.searchInput.hint = hint
        binding.searchButton.setOnClickListener { setActive(true) }
        binding.searchClose.setOnClickListener { setActive(false) }
        binding.searchInput.doAfterTextChanged { onQueryChange(it?.toString().orEmpty()) }
        binding.searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                imm(v.context).hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        binding.titleRow.isVisible = !value
        binding.searchRow.isVisible = value
        val input = binding.searchInput
        if (value) {
            input.requestFocus()
            imm(input.context).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        } else {
            input.setText("")
            imm(input.context).hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        onActiveChange(value)
    }

    private fun imm(context: Context) = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}
