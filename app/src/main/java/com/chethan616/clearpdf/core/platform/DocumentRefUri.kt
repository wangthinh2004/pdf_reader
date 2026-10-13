package com.chethan616.clearpdf.core.platform

import android.net.Uri
import com.chethan616.clearpdf.domain.model.DocumentRef

/** The only place DocumentRef meets android.net.Uri; used at the data and view boundaries. */
fun Uri.toDocumentRef(): DocumentRef = DocumentRef(toString())

fun DocumentRef.toUri(): Uri = Uri.parse(value)
