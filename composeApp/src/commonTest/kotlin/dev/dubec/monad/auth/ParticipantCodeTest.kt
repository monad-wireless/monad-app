package dev.dubec.monad.auth

import dev.dubec.monad.auth.data.dto.MeResponseDto
import dev.dubec.monad.auth.domain.ParticipantCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParticipantCodeTest {

    @Test
    fun theServersCodeIsTheIdOnEveryPhone() {
        assertEquals("p-0123456789abcdef", ParticipantCode.of("p-0123456789abcdef", localId = 1))
        assertEquals("p-0123456789abcdef", ParticipantCode.of("p-0123456789abcdef", localId = 7))
    }

    @Test
    fun aMissingCodeNeverPassesForOne() {
        for (missing in listOf(null, "", "  ")) {
            assertEquals("local-7", ParticipantCode.of(missing, localId = 7))
        }
    }

    @Test
    fun anOlderBackendStillParses() {
        val json = Json { ignoreUnknownKeys = true }
        assertNull(json.decodeFromString<MeResponseDto>("""{"email":"a@b.c","name":null}""").participantCode)
        assertEquals(
            "p-0123456789abcdef",
            json.decodeFromString<MeResponseDto>(
                """{"email":"a@b.c","is_operator":true,"participant_code":"p-0123456789abcdef"}"""
            ).participantCode,
        )
    }
}
