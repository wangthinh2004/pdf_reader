package com.chethan616.clearpdf.core.dispatchers

import javax.inject.Qualifier

/** Dispatchers.IO: file-system queries, preferences, network. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher

/** Dispatchers.Default: CPU-bound work. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher

/** Bounded pool for heavy PDF rendering / compression (see AppDispatchers.pdf). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PdfDispatcher
