package org.session.libsession.messaging.messages.control

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.utilities.Address
import org.session.protos.SessionProtos
import java.io.ByteArrayOutputStream

/** An Xsession content extension carried inside the signed Session envelope. */
class MessageEdit(
    val originalTimestamp: Long,
    val body: String,
    var syncTarget: String? = null,
) : ControlMessage() {
    override val isSelfSendValid = true

    override fun shouldDiscardIfBlocked() = true

    override fun isValid(): Boolean =
        super.isValid() && originalTimestamp > 0 &&
            (sentTimestamp ?: 0) > originalTimestamp &&
            body.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES &&
            (syncTarget == null || isStandardAddress(syncTarget))

    override fun buildProto(builder: SessionProtos.Content.Builder, messageDataProvider: MessageDataProvider) {
        val payload = encode {
            writeUInt64(1, originalTimestamp)
            writeString(2, body)
            syncTarget?.let { writeString(3, it) }
        }
        builder.mergeFrom(encode { writeByteArray(EXTENSION_FIELD, payload) })
    }

    companion object {
        // Private field on Content. Keep the payload compatible across Xsession versions.
        private const val EXTENSION_FIELD = 55001
        const val MAX_BODY_BYTES = 40_000

        fun fromProto(proto: SessionProtos.Content): MessageEdit? {
            val content = CodedInputStream.newInstance(proto.toByteArray())
            while (!content.isAtEnd) {
                val tag = content.readTag()
                if (WireFormat.getTagFieldNumber(tag) == EXTENSION_FIELD &&
                    WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
                ) {
                    return decodePayload(content.readByteArray())
                }
                if (!content.skipField(tag)) break
            }
            return null
        }

        private fun decodePayload(bytes: ByteArray): MessageEdit? {
            val input = CodedInputStream.newInstance(bytes)
            var timestamp = 0L
            var body: String? = null
            var syncTarget: String? = null
            while (!input.isAtEnd) {
                when (val tag = input.readTag()) {
                    8 -> timestamp = input.readUInt64()
                    18 -> body = input.readStringRequireUtf8()
                    26 -> syncTarget = input.readStringRequireUtf8()
                    else -> if (!input.skipField(tag)) break
                }
            }
            return body?.let { MessageEdit(timestamp, it, syncTarget) }
        }

        private fun isStandardAddress(value: String?): Boolean =
            runCatching { value?.let(Address::fromSerialized) is Address.Standard }.getOrDefault(false)

        private fun encode(write: CodedOutputStream.() -> Unit): ByteArray {
            val bytes = ByteArrayOutputStream()
            CodedOutputStream.newInstance(bytes).apply {
                write()
                flush()
            }
            return bytes.toByteArray()
        }
    }
}
