package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.session.libsession.messaging.messages.control.MessageEdit
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.database.model.MessageChanges
import org.thoughtcrime.securesms.database.model.MessageRecord
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class MessageEditDatabase @Inject constructor(
    @ApplicationContext context: Context,
    databaseHelper: Provider<SQLCipherOpenHelper>,
    private val messages: Provider<MmsSmsDatabase>,
    private val clock: SnodeClock,
) : Database(context, databaseHelper) {

    private val changeNotifications = MutableSharedFlow<MessageChanges>(extraBufferCapacity = 64)
    val changes = changeNotifications.asSharedFlow()

    fun apply(threadId: Long, author: String, authorIsSelf: Boolean, edit: MessageEdit) {
        val revision = edit.sentTimestamp ?: return
        val now = clock.currentTimeMillis()
        if (revision > now + TimeUnit.MINUTES.toMillis(5)) return
        val target = messages.get().getMessageFor(threadId, edit.originalTimestamp, author, false)
        if (target != null) {
            if (target.isOutgoing == authorIsSelf && canEditBody(target)) {
                update(target, revision, edit.body)
            }
            return
        }

        // A swarm can deliver the edit before the original. Retain only the newest edit per target.
        val db = writableDatabase
        db.execSQL("DELETE FROM $PENDING_TABLE WHERE received_at < ?", arrayOf(now - PENDING_TTL))
        db.execSQL(
            """INSERT INTO $PENDING_TABLE (thread_id, author, original_date, revision, body, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (thread_id, author, original_date) DO UPDATE SET
                    revision = excluded.revision, body = excluded.body, received_at = excluded.received_at
                WHERE excluded.revision > revision""",
            arrayOf<Any>(threadId, author, edit.originalTimestamp, revision, edit.body, now),
        )
        db.execSQL(
            """DELETE FROM $PENDING_TABLE WHERE thread_id = ?1 AND author = ?2 AND original_date IN (
                SELECT original_date FROM $PENDING_TABLE WHERE thread_id = ?1 AND author = ?2
                ORDER BY received_at DESC, original_date DESC LIMIT -1 OFFSET 100
            )""",
            arrayOf<Any>(threadId, author),
        )
    }

    fun applyPending(threadId: Long, author: String, authorIsSelf: Boolean, originalTimestamp: Long) {
        val db = writableDatabase
        val args = arrayOf(threadId.toString(), author, originalTimestamp.toString())
        val pending = db.query(
            PENDING_TABLE, arrayOf("revision", "body", "received_at"), TARGET_WHERE, args,
            null, null, null,
        ).use { cursor ->
            if (cursor.moveToFirst() && cursor.getLong(2) >= clock.currentTimeMillis() - PENDING_TTL) {
                cursor.getLong(0) to cursor.getString(1)
            } else null
        }
        if (pending != null) {
            val target = messages.get().getMessageFor(threadId, originalTimestamp, author, false)
            if (target == null) return
            if (target.isOutgoing == authorIsSelf && canEditBody(target)) {
                update(target, pending.first, pending.second)
            }
        }
        db.delete(PENDING_TABLE, TARGET_WHERE, args)
    }

    private fun update(target: MessageRecord, revision: Long, body: String) {
        val table = if (target.isMms) MmsDatabase.TABLE_NAME else SmsDatabase.TABLE_NAME
        val dateColumn = if (target.isMms) MmsDatabase.DATE_SENT else SmsDatabase.DATE_SENT
        val changed = writableDatabase.update(
            table,
            ContentValues().apply {
                put(MmsSmsColumns.BODY, body)
                put(EDITED_AT, revision)
            },
            "${MmsSmsColumns.ID} = ? AND ${MmsSmsColumns.THREAD_ID} = ? AND $dateColumn = ? " +
                "AND $EDITED_AT < ? AND NOT ${MmsSmsColumns.IS_DELETED}",
            arrayOf(target.id.toString(), target.threadId.toString(), target.dateSent.toString(), revision.toString()),
        )
        if (changed > 0) {
            changeNotifications.tryEmit(MessageChanges(MessageChanges.ChangeType.Updated, target.messageId, target.threadId))
        }
    }

    companion object {
        const val EDITED_AT = "xsession_edited_at"
        private const val PENDING_TABLE = "xsession_pending_edits"
        private const val TARGET_WHERE = "thread_id = ? AND author = ? AND original_date = ?"
        private val PENDING_TTL = TimeUnit.DAYS.toMillis(2)

        fun supports(address: Address): Boolean = address is Address.Standard || address is Address.Group

        fun canEditBody(message: MessageRecord): Boolean =
            message.body.isNotBlank() && !message.isDeleted && !message.isControlMessage &&
                !message.isUnsupportedMessage && !message.isOpenGroupInvitation &&
                !message.isPending && !message.isFailed && message.expiresIn <= 0

        @JvmStatic
        fun createSchema(db: SQLiteDatabase) {
            for (table in listOf(SmsDatabase.TABLE_NAME, MmsDatabase.TABLE_NAME)) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $EDITED_AT INTEGER NOT NULL DEFAULT 0")
            }
            db.execSQL(
                """CREATE TABLE $PENDING_TABLE (
                    thread_id INTEGER NOT NULL, author TEXT NOT NULL, original_date INTEGER NOT NULL,
                    revision INTEGER NOT NULL, body TEXT NOT NULL, received_at INTEGER NOT NULL,
                    PRIMARY KEY (thread_id, author, original_date)
                ) WITHOUT ROWID"""
            )
            db.execSQL("CREATE INDEX ${PENDING_TABLE}_by_age ON $PENDING_TABLE (received_at)")
            db.execSQL(
                """CREATE TRIGGER xsession_delete_pending_edits AFTER DELETE ON ${ThreadDatabase.TABLE_NAME}
                    BEGIN DELETE FROM $PENDING_TABLE WHERE thread_id = OLD.${ThreadDatabase.ID}; END"""
            )
        }
    }
}
