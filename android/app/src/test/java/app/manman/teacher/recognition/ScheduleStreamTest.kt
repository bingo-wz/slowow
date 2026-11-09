package app.manman.teacher.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader
import java.time.LocalTime

class ScheduleStreamTest {
    @Test fun readsActualPhasesAndKeepsUnknownFieldsForReview() {
        val stream = """
            data: {"type":"phase","phase":"received"}

            data: {"type":"heartbeat"}

            data: {"type":"phase","phase":"generating"}

            data: {"type":"result","result":{"slots":[{"weekday":1,"title":"第一节","start":"09:05","end":"09:45"},{"weekday":0,"title":"第二节","start":null,"end":null}],"lessons":[{"weekday":1,"slot":"第一节","subject":"虚构语文"}],"warnings":["请核对"]}}

        """.trimIndent()
        val phases = mutableListOf<SchedulePhase>()
        val result = readScheduleEvents(StringReader(stream).buffered()) { phases.add(it) }
        assertEquals(listOf(SchedulePhase.WAITING, SchedulePhase.RECEIVING), phases)
        assertEquals(LocalTime.of(9, 5), result.slots.first().start)
        assertNull(result.slots.last().start)
        assertEquals(0, result.slots.last().weekday)
        assertEquals("虚构语文", result.lessons.single().subject)
    }

    @Test fun interruptedAndErrorStreamsCannotBecomeAResult() {
        assertThrows(IllegalArgumentException::class.java) {
            readScheduleEvents(StringReader("data: {\"type\":\"heartbeat\"}\n\n").buffered()) { }
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            readScheduleEvents(StringReader("data: {\"type\":\"error\",\"message\":\"识别未完成\"}\n\n").buffered()) { }
        }
        assertEquals("识别未完成", error.message)
    }
}
