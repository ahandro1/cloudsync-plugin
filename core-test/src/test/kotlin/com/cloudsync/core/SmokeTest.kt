package com.cloudsync.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun toRelMapsPerProfileKey() {
        assertEquals("p/video_pos_dur/55", Keys.toRel("2/video_pos_dur/55", 2))
    }

    @Test
    fun toLocalMapsHeaderKey() {
        assertEquals("download_header_cache/55", Keys.toLocal("h/55", 0))
    }
}
