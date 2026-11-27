package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraApiTest {

    @Test fun parsesDvrStateFromTheA200() {
        // resposta real da A200 em 27/11/2026 (com o cabeçalho HTTP na frente, como o nc devolve)
        val body = "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\n" +
            """{"ResultCode":"0","Result":{"rec":"1","backcam":"1","sensor":"0","parking":"0","timelapse":"0"}}"""
        val st = CameraApi.DvrState.parse(body)!!
        assertEquals(true, st.recording)
        assertEquals(true, st.rearConnected)
        assertEquals(false, st.rearActive)
        assertEquals(false, st.parking)
    }

    @Test fun rearActiveAndNotRecording() {
        val st = CameraApi.DvrState.parse("""{"ResultCode":"0","Result":{"rec":"0","backcam":"1","sensor":"1","parking":"0"}}""")!!
        assertEquals(false, st.recording)
        assertEquals(true, st.rearActive)
    }

    @Test fun refusedOrGarbageIsNull() {
        assertNull(CameraApi.DvrState.parse("""{"ResultCode":"-4444"}"""))
        assertNull(CameraApi.DvrState.parse("sem json"))
    }
}
