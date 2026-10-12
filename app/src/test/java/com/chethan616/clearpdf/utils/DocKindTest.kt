package com.chethan616.clearpdf.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DocKindTest {
    @Test fun hwpAndHwpxAreHwp() {
        assertEquals(DocKind.Hwp, docKindOf("보고서.hwp"))
        assertEquals(DocKind.Hwp, docKindOf("report.hwpx"))
        assertEquals(DocKind.Hwp, docKindOf("REPORT.HWP"))
    }

    @Test fun txtIsTxt() {
        assertEquals(DocKind.Txt, docKindOf("notes.txt"))
        assertEquals(DocKind.Txt, docKindOf("NOTE.TXT"))
    }

    @Test fun otherTextFormatsStayOther() {
        assertEquals(DocKind.Other, docKindOf("data.csv"))
        assertEquals(DocKind.Other, docKindOf("readme.md"))
        assertEquals(DocKind.Other, docKindOf("a.hwp.zip"))
    }
}
