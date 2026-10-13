package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.PdfDispatcher
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.data.mapper.toDomain
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.repository.OcrRepository
import com.kyant.ocrcore.OcrService
import com.kyant.pdfcore.raster.PdfRasterizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class OcrRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrService: OcrService,
    @PdfDispatcher private val pdf: CoroutineDispatcher
) : OcrRepository {
    override suspend fun recognizePage(source: DocumentInfo, pageIndex: Int): List<OcrWord> = withContext(pdf) {
        val bitmap = PdfRasterizer.rasterizePageBitmap(context, source.ref.toUri(), pageIndex)
            ?: return@withContext emptyList()
        try {
            ocrService.recognize(context, bitmap).words.map { it.toDomain() }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
