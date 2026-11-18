package org.session.libsession.messaging.messages.control

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.session.protos.SessionProtos

class MessageEditTest {
    private val author = "05" + "11".repeat(32)
    private val recipientId = "05" + "22".repeat(32)

    @Test
    fun survivesContentSerializationWithSyncTarget() {
        val sent = MessageEdit(100L, "Скоро буду 👋", recipientId).withEnvelope()
        val builder = SessionProtos.Content.newBuilder()
        sent.toProto(builder, mockk())
        val proto = SessionProtos.Content.parseFrom(builder.build().toByteArray())
        val received = requireNotNull(MessageEdit.fromProto(proto)).withEnvelope()

        assertEquals(sent.originalTimestamp, received.originalTimestamp)
        assertEquals(sent.body, received.body)
        assertEquals(sent.syncTarget, received.syncTarget)
        assertTrue(received.isValid())
        assertFalse(proto.hasDataMessage())
    }

    @Test
    fun rejectsBlankBodiesBackdatedRevisionsAndInvalidSyncTargets() {
        assertFalse(MessageEdit(100L, "  ").withEnvelope().isValid())
        assertFalse(MessageEdit(200L, "Edited").withEnvelope().isValid())
        assertFalse(MessageEdit(100L, "Edited", "https://example.com").withEnvelope().isValid())
        assertNull(MessageEdit.fromProto(SessionProtos.Content.getDefaultInstance()))
    }

    private fun MessageEdit.withEnvelope() = apply {
        sender = author
        recipient = recipientId
        sentTimestamp = 200L
        receivedTimestamp = 201L
    }
}
