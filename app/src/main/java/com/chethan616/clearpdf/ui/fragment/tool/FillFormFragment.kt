package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ToolFillFormBinding
import com.chethan616.clearpdf.ui.common.UnsavedChanges
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.fragment.settings.bindToggle
import com.chethan616.clearpdf.ui.viewmodel.FillFormUiState
import com.chethan616.clearpdf.ui.viewmodel.FillFormViewModel
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kyant.pdfcore.form.PdfFormService

class FillFormFragment : ToolFragment<ToolFillFormBinding>() {

    override val titleRes = R.string.tool_fill_form

    private val vm: FillFormViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }

    /** Field name → its input view, rebuilt when a new form is loaded. */
    private val inputs = mutableMapOf<String, View>()
    private var builtFor: List<String> = emptyList()

    private val backGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = confirmExit()
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolFillFormBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_fill_form)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backGuard)
        ToolUi.hero(
            body.hero, R.drawable.ic_edit_note, accent,
            getString(R.string.tool_fill_form), getString(R.string.tool_fill_form_sub),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }
        body.header.icon.setImageResource(R.drawable.ic_edit_note)
        body.header.icon.tint(accent)
        body.header.title.setText(R.string.fill_form_fields)
        bindToggle(
            ctx, body.flatten, R.drawable.ic_layers,
            getString(R.string.fill_form_flatten), getString(R.string.fill_form_flatten_desc),
            vm.uiState.value.flatten, vm::onFlattenChange
        )
        ToolUi.primaryButton(body.run, accent)
        body.run.setOnClickListener { vm.save(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    override fun onDestroyView() {
        inputs.clear()
        builtFor = emptyList()
        super.onDestroyView()
    }

    override fun onBackClicked() {
        if (vm.uiState.value.let { it.dirty && !it.isProcessing }) confirmExit() else super.onBackClicked()
    }

    private fun confirmExit() {
        UnsavedChanges.confirm(
            requireContext(),
            onSave = { vm.save(requireContext(), onSaved = { findNavController().popBackStack() }) },
            onDiscard = { findNavController().popBackStack() }
        )
    }

    private fun render(state: FillFormUiState) {
        val accent = requireContext().color(R.color.tool_fill_form)
        backGuard.isEnabled = state.dirty && !state.isProcessing
        val hasFields = state.fields.isNotEmpty()
        body.formCard.isVisible = hasFields
        body.run.isVisible = hasFields
        body.count.text = "${state.fields.size}"
        val names = state.fields.map { it.name }
        if (names != builtFor) buildFields(state.fields)
        state.fields.forEach { field ->
            when (val input = inputs[field.name]) {
                is MaterialSwitch -> (field.value == "true").let { if (input.isChecked != it) input.isChecked = it }
                is TextInputEditText -> ToolUi.syncText(input, field.value)
            }
        }
        if (body.flatten.toggle.isChecked != state.flatten) body.flatten.toggle.isChecked = state.flatten
        ToolUi.setBusy(
            body.run, state.isProcessing, R.drawable.ic_edit_note,
            getString(if (state.isProcessing) R.string.fill_form_working else R.string.fill_form_action)
        )
        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }

    private fun buildFields(fields: List<PdfFormService.FormField>) {
        val ctx = requireContext()
        body.fields.removeAllViews()
        inputs.clear()
        builtFor = fields.map { it.name }
        fields.forEach { field ->
            if (field.type == PdfFormService.FieldType.CHECKBOX) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = ctx.dp(48)
                }
                row.addView(TextView(ctx).apply {
                    text = field.name
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ctx.color(R.color.app_text))
                    textSize = 14f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val toggle = MaterialSwitch(ctx).apply {
                    isChecked = field.value == "true"
                    isSaveEnabled = false
                    setOnCheckedChangeListener { _, on -> vm.onFieldChange(field.name, if (on) "true" else "false") }
                }
                row.addView(toggle)
                inputs[field.name] = toggle
                body.fields.addView(row)
            } else {
                val layout = layoutInflater.inflate(R.layout.view_text_field, body.fields, false) as TextInputLayout
                layout.hint = field.name
                if (field.options.isNotEmpty()) layout.helperText = field.options.joinToString(" · ")
                val edit = layout.findViewById<TextInputEditText>(R.id.input)
                edit.inputType = InputType.TYPE_CLASS_TEXT
                // Every field shares one view id; the view model owns the values, so skip view-state restore.
                edit.isSaveEnabled = false
                edit.setText(field.value)
                edit.doAfterTextChanged { vm.onFieldChange(field.name, it?.toString().orEmpty()) }
                inputs[field.name] = edit
                body.fields.addView(layout)
            }
        }
    }
}
