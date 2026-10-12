package com.chethan616.clearpdf.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.fragment.findNavController
import com.chethan616.clearpdf.MainActivity
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.AppSettingsManager
import com.chethan616.clearpdf.data.repository.OnboardingManager
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.domain.usecase.CompressPdfUseCase
import com.chethan616.clearpdf.domain.usecase.CreatePdfUseCase
import com.chethan616.clearpdf.domain.usecase.MergePdfUseCase
import com.chethan616.clearpdf.domain.usecase.OpenPdfUseCase
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.isDarkTheme
import com.chethan616.clearpdf.ui.components.LocalBackToLibraryAction
import com.chethan616.clearpdf.ui.screen.*
import com.chethan616.clearpdf.ui.theme.LocalIsDarkMode
import com.chethan616.clearpdf.ui.viewmodel.*
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * TEMPORARY bridge while the app moves from Compose to XML: hosts a screen that has not been
 * rewritten yet, picked by the `route` argument. Each screen leaves this file as its Fragment lands.
 */
class ComposeBridgeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val route = requireArguments().getString("route").orEmpty()
        val uri = AppNavigator.parseUriArg(arguments?.getString(AppNavigator.ARG_URI))
        val dark = requireContext().isDarkTheme()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val backToLibrary = remember {
                    {
                        val nav = findNavController()
                        if (!nav.popBackStack(R.id.home, false)) AppNavigator.open(nav, R.id.home)
                    }
                }
                CompositionLocalProvider(
                    LocalIsDarkMode provides dark,
                    LocalBackToLibraryAction provides backToLibrary
                ) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.layerBackdrop(backdrop).fillMaxSize()
                                .background(if (dark) Color(0xFF1C1C1E) else Color(0xFFE9E9EE))
                        )
                        Route(route, uri, backdrop)
                    }
                }
            }
        }
    }

    private fun back() {
        findNavController().popBackStack()
    }

    private fun viewPdf(uri: android.net.Uri) = AppNavigator.openPdfViewer(findNavController(), uri)

    private inline fun <reified T : ViewModel> factory(crossinline create: () -> T) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
    }

    @Composable
    private fun Route(route: String, uri: android.net.Uri?, backdrop: LayerBackdrop) {
        val context = LocalContext.current
        when (route) {
            "image_editor" -> {
                val vm: ImageEditorViewModel = viewModel()
                LaunchedEffect(uri) { if (uri != null) vm.load(context, uri) }
                ImageEditorScreen(backdrop = backdrop, viewModel = vm, onBack = ::back, onOpenPdf = ::viewPdf)
            }
        }
    }
}
