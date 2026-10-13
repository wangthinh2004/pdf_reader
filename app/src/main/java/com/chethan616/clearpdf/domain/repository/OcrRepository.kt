package com.chethan616.clearpdf.domain.repository

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord

/** On-device text recognition of rendered PDF pages. */
interface OcrRepository {
    /** Empty when the page can't be rendered. */
    suspend fun recognizePage(source: DocumentInfo, pageIndex: Int): List<OcrWord>
}
