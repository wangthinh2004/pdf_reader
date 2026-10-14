package com.chethan616.clearpdf.core.di

import com.chethan616.clearpdf.data.repository.DocumentRepositoryImpl
import com.chethan616.clearpdf.data.repository.EngagementRepositoryImpl
import com.chethan616.clearpdf.data.repository.FavoritesRepositoryImpl
import com.chethan616.clearpdf.data.repository.LibraryRepositoryImpl
import com.chethan616.clearpdf.data.repository.OcrRepositoryImpl
import com.chethan616.clearpdf.data.repository.PdfRepositoryImpl
import com.chethan616.clearpdf.data.repository.RecentFilesRepositoryImpl
import com.chethan616.clearpdf.data.repository.SettingsRepositoryImpl
import com.chethan616.clearpdf.data.repository.SummaryRepositoryImpl
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.EngagementRepository
import com.chethan616.clearpdf.domain.repository.FavoritesRepository
import com.chethan616.clearpdf.domain.repository.LibraryRepository
import com.chethan616.clearpdf.domain.repository.OcrRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import com.chethan616.clearpdf.domain.repository.SettingsRepository
import com.chethan616.clearpdf.domain.repository.SummaryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun documents(impl: DocumentRepositoryImpl): DocumentRepository
    @Binds @Singleton abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds @Singleton abstract fun recentFiles(impl: RecentFilesRepositoryImpl): RecentFilesRepository
    @Binds @Singleton abstract fun engagement(impl: EngagementRepositoryImpl): EngagementRepository
    @Binds @Singleton abstract fun pdf(impl: PdfRepositoryImpl): PdfRepository
    @Binds @Singleton abstract fun ocr(impl: OcrRepositoryImpl): OcrRepository
    @Binds @Singleton abstract fun summary(impl: SummaryRepositoryImpl): SummaryRepository
    @Binds @Singleton abstract fun library(impl: LibraryRepositoryImpl): LibraryRepository
    @Binds @Singleton abstract fun favorites(impl: FavoritesRepositoryImpl): FavoritesRepository
}
