package com.chethan616.clearpdf.core.di

import com.chethan616.clearpdf.core.dispatchers.DefaultDispatcher
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.core.dispatchers.PdfDispatcher
import com.chethan616.clearpdf.ui.utils.AppDispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    // The same pool the not-yet-migrated screens use, so PDF work stays bounded app-wide.
    @Provides @PdfDispatcher fun pdfDispatcher(): CoroutineDispatcher = AppDispatchers.pdf
}
