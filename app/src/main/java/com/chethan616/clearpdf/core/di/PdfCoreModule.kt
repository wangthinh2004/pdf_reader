package com.chethan616.clearpdf.core.di

import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.kyant.ocrcore.OcrService
import com.kyant.pdfcore.compressor.PdfCompressor
import com.kyant.pdfcore.converter.PdfConverter
import com.kyant.pdfcore.viewer.PdfViewer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * pdf-core / ocr-core services. They delegate to PdfServiceLocator so migrated and
 * not-yet-migrated screens share ONE instance each: PdfViewerImpl serializes page access with
 * its own lock, and a second instance would break that. Phase 6 moves construction here.
 */
@Module
@InstallIn(SingletonComponent::class)
object PdfCoreModule {
    @Provides fun pdfViewer(): PdfViewer = PdfServiceLocator.pdfViewer
    @Provides fun pdfCompressor(): PdfCompressor = PdfServiceLocator.pdfCompressor
    @Provides fun pdfConverter(): PdfConverter = PdfServiceLocator.pdfConverter
    @Provides fun ocrService(): OcrService = PdfServiceLocator.ocrService
}
