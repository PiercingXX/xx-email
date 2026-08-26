package dev.xxemail.data.repo

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.xxemail.data.api.GmailApi
import dev.xxemail.data.api.Message
import dev.xxemail.data.api.MimeComposer
import dev.xxemail.data.api.ModifyLabelsRequest
import dev.xxemail.data.api.Thread
import dev.xxemail.data.db.AccountDao
import dev.xxemail.data.db.AccountEntity
import dev.xxemail.data.db.LabelDao
import dev.xxemail.data.db.LabelEntity
import dev.xxemail.data.db.MessageDao
import dev.xxemail.data.db.MessageEntity
import dev.xxemail.data.db.OutboxDao
import dev.xxemail.data.db.OutboxEntity
import dev.xxemail.data.db.OutboxKind
import dev.xxemail.data.db.OutboxState
import dev.xxemail.data.db.SnoozeWakeDao
import dev.xxemail.data.db.SnoozeWakeEntity
import dev.xxemail.data.db.ThreadDao
import dev.xxemail.data.db.ThreadEntity
import dev.xxemail.domain.AddressUtils
import dev.xxemail.domain.MailboxFolder
import dev.xxemail.domain.SafePaths
import dev.xxemail.sync.OutboxFiles
import dev.xxemail.sync.OutboxWorker
import dev.xxemail.sync.SnoozeWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/** An action the user can reverse via snackbar while the window is open. */
class Undoable(val message: String, val revert: suspend () -> Unit)

data class ComposeRequest(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val bodyText: String,
    val threadId: String? = null,
    val inReplyToMessageId: String? = null,
    val referencesHeader: String? = null,
    val attachmentFiles: List<File> = emptyList(),
)

class MailRepository(
    private val accountEmail: String,
    private val api: GmailApi,
    private val accountDao: AccountDao,
    private val labelDao: LabelDao,
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val wakeDao: SnoozeWakeDao,
    private val settings: SettingsRepository,
    private val workManager: WorkManager,
    private val appContext: Context,
) {

    // ------------------------------------------------------------------ observe

    fun observeFolder(folder: MailboxFolder): Flow<List<ThreadEntity>> = when (folder) {
        MailboxFolder.PRIMARY, MailboxFolder.SOCIAL, MailboxFolder.PROMOTIONS,
        MailboxFolder.UPDATES, MailboxFolder.FORUMS,
        ->
            threadDao.observeInboxCategory(accountEmail, folder.category.orEmpty(), folder.includeEmptyPrimary)
        MailboxFolder.STARRED -> threadDao.observeStarred(accountEmail)
        MailboxFolder.SNOOZED -> threadDao.observeSnoozed(accountEmail)
        MailboxFolder.ALL_MAIL -> threadDao.observeAllMail(accountEmail)
        else -> threadDao.observeWithLabel(accountEmail, folder.labelId!!)
    }

    fun observeThread(threadId: String): Flow<List<MessageEntity>> =
        messageDao.observeForThread(accountEmail, threadId)

    fun observeThreadRow(threadId: String): Flow<ThreadEntity?> = threadDao.observe(accountEmail, threadId)

    fun observeLabels(): Flow<List<LabelEntity>> = labelDao.observeForAccount(accountEmail)

    // ------------------------------------------------------------------ sync

    data class SyncResult(val newInboxThreads: List<ThreadEntity>)

    /** Full or delta sync. Never throws; failures are logged and returned. */
    suspend fun sync(forceFull: Boolean = false): Result<SyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = accountDao.get(accountEmail)
            val profile = api.getProfile()
            accountDao.upsert(
                existing?.copy(historyId = profile.historyId ?: existing.historyId)
                    ?: AccountEntity(email = accountEmail, displayName = profile.emailAddress, historyId = profile.historyId),
            )
            syncLabels()

            val beforeSyncAt = System.currentTimeMillis()
            val storedHistoryId = existing?.historyId
            val endHistoryId = profile.historyId
            if (storedHistoryId == null || forceFull || endHistoryId == null) {
                initialSync()
                accountDao.updateSyncPoint(accountEmail, endHistoryId, System.currentTimeMillis())
            } else {
                deltaSync(storedHistoryId, endHistoryId)
            }

            val newThreads = threadDao.inboxAll(accountEmail)
                .filter { it.date > beforeSyncAt && it.unreadCount > 0 }
            Result.success(SyncResult(newThreads))
        }.getOrElse {
            Log.w(TAG, "sync failed for $accountEmail", it)
            Result.failure(it)
        }
    }

    private suspend fun syncLabels() {
        val rows = api.listLabels().labels.map { label ->
            LabelEntity(
                accountEmail = accountEmail,
                id = label.id,
                name = label.name,
                type = label.type,
                colorBg = label.color?.background,
                colorText = label.color?.text,
            )
        }
        if (rows.isNotEmpty()) labelDao.upsertAll(rows)
    }

    /** Pull recent INBOX pages (bounded — keeps first-sync quota sane). */
    private suspend fun initialSync() {
        var pageToken: String? = null
        var pages = 0
        val threadIds = LinkedHashSet<String>()
        do {
            val page = api.listThreads(labelIds = listOf("INBOX"), maxResults = 50, pageToken = pageToken)
            page.threads.forEach { threadIds.add(it.id) }
            pageToken = page.nextPageToken
            pages++
        } while (pageToken != null && pages < 4)
        hydrateThreads(threadIds.toList())
    }

    /**
     * Delta sync via history.list (2 quota units). Affected threads are rebuilt wholesale
     * from threads.get (10 units each, capped per pass; overflow stops paging so the next
     * pass resumes at the last fully processed history id).
     * HTTP 404 ⇒ stored historyId expired ⇒ bounded full resync.
     *
     * History ids are opaque uint64 strings from Gmail — they are only ever compared or
     * persisted verbatim, never arithmetically.
     */
    private suspend fun deltaSync(startHistoryId: String, fallbackEnd: String?) {
        val touchedThreadIds = LinkedHashSet<String>()
        val messageIdsNeedingLookup = LinkedHashSet<String>()
        val deletedMessageIds = LinkedHashSet<String>()
        var lastProcessedId = startHistoryId
        var sawAnyItem = false
        var pageToken: String? = null

        try {
            do {
                val page = api.listHistory(startHistoryId = startHistoryId, pageToken = pageToken)
                page.history.forEach { item ->
                    sawAnyItem = true
                    item.messagesAdded.forEach { added ->
                        val m = added.message
                        if (m?.threadId != null) touchedThreadIds.add(m.threadId!!)
                        else m?.id?.let(messageIdsNeedingLookup::add)
                    }
                    item.labelsAdded.forEach { change ->
                        change.message?.threadId?.let(touchedThreadIds::add)
                            ?: change.message?.id?.let(messageIdsNeedingLookup::add)
                    }
                    item.labelsRemoved.forEach { change ->
                        change.message?.threadId?.let(touchedThreadIds::add)
                            ?: change.message?.id?.let(messageIdsNeedingLookup::add)
                    }
                    item.messagesDeleted.forEach { removed -> removed.message?.id?.let(deletedMessageIds::add) }
                }
                pageToken = page.nextPageToken
                val overBudget =
                    touchedThreadIds.size + messageIdsNeedingLookup.size > MAX_THREADS_PER_DELTA
                if (overBudget && pageToken != null) {
                    // More work than one pass can hydrate. Stop paging without advancing the
                    // checkpoint past this page; the next sync resumes right here.
                    break
                }
                // Page fully processed → its last history id becomes the checkpoint.
                page.history.lastOrNull()?.let { lastProcessedId = it.id }
            } while (pageToken != null)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                Log.i(TAG, "history expired for $accountEmail — full resync")
                initialSync()
                return
            }
            throw e
        }

        // Resolve message-only refs to their thread ids.
        messageIdsNeedingLookup.forEach { mid ->
            runCatching { api.getMessage(mid, format = "metadata") }.getOrNull()?.threadId?.let(touchedThreadIds::add)
        }

        deletedMessageIds.forEach { mid ->
            val cached = messageDao.get(accountEmail, mid)
            cached ?: return@forEach
            messageDao.deleteById(accountEmail, mid)
            if (messageDao.countForThread(accountEmail, cached.threadId) == 0) {
                threadDao.deleteByIds(accountEmail, listOf(cached.threadId))
            }
        }

        val capped = touchedThreadIds.take(MAX_THREADS_PER_DELTA)
        hydrateThreads(capped)

        // Persist the last fully processed history id string as-is. With an empty window,
        // fall back to the profile's current id so the checkpoint still advances.
        val checkpoint = if (sawAnyItem) lastProcessedId else fallbackEnd ?: startHistoryId
        accountDao.updateSyncPoint(accountEmail, checkpoint, System.currentTimeMillis())
    }

    /** One threads.get per id → aggregate row + per-message rows. */
    private suspend fun hydrateThreads(threadIds: Collection<String>) {
        if (threadIds.isEmpty()) return
        val threadRows = mutableListOf<ThreadEntity>()
        val messageRows = mutableListOf<MessageEntity>()
        threadIds.forEach { id ->
            runCatching { api.getThread(id, format = "metadata") }
                .onFailure { Log.w(TAG, "threads.get($id) failed", it) }
                .getOrNull()
                ?.let { full ->
                    threadRows += toThreadEntity(full)
                    messageRows += full.messages.map { toMessageEntity(it, full.id) }
                }
        }
        if (threadRows.isNotEmpty()) threadDao.upsertAll(threadRows)
        if (messageRows.isNotEmpty()) messageDao.upsertAll(messageRows)
    }

    /** Ensure a rarely-used folder has content; hydrates once from the server. */
    suspend fun ensureHydrated(folder: MailboxFolder) {
        when {
            folder.category != null -> {
                if (threadDao.count(accountEmail) == 0) {
                    hydrateFromQuery(labelIds = listOf("INBOX", folder.category!!))
                }
            }
            folder.labelId != null -> {
                if (threadDao.countWithLabel(accountEmail, folder.labelId!!) == 0) {
                    hydrateFromQuery(labelIds = listOf(folder.labelId!!))
                }
            }
            else -> Unit // STARRED / SNOOZED / ALL_MAIL are served from local cache
        }
    }

    private suspend fun hydrateFromQuery(labelIds: List<String>) {
        // Gmail excludes TRASH/SPAM unless includeSpamTrash is set — required to hydrate those folders.
        val page = api.listThreads(
            labelIds = labelIds,
            maxResults = 30,
            includeSpamTrash = labelIds.any { it == "TRASH" || it == "SPAM" },
        )
        hydrateThreads(page.threads.map { it.id })
    }

    // ------------------------------------------------------------------ reading

    data class AttachmentMeta(
        val messageId: String,
        val attachmentId: String,
        val filename: String,
        val mimeType: String,
        val size: Int,
    )

    data class FullMessage(
        val entity: MessageEntity,
        val html: String?,
        val plain: String?,
        val attachments: List<AttachmentMeta>,
    )

    private val fullCache = LinkedHashMap<String, List<FullMessage>>()

    /** Fetches full bodies for a thread (lazy — sync stores metadata only). */
    suspend fun loadFullThread(threadId: String): List<FullMessage> = withContext(Dispatchers.IO) {
        fullCache[threadId]?.let { return@withContext it }
        val result = messageDao.listForThread(accountEmail, threadId).map { row ->
            if (row.bodyFetched) {
                FullMessage(row, row.bodyHtml, row.bodyPlain, emptyList())
            } else {
                val full = runCatching { api.getMessage(row.id, format = "full") }.getOrNull()
                val html = full?.findBody("text/html")?.body?.data?.let(::decodeBase64Url)
                val plain = full?.findBody("text/plain")?.body?.data?.let(::decodeBase64Url)
                val atts = full?.attachments().orEmpty()
                messageDao.updateBodies(accountEmail, row.id, html, plain)
                FullMessage(row.copy(bodyHtml = html, bodyPlain = plain, bodyFetched = true), html, plain, atts)
            }
        }
        fullCache[threadId] = result
        while (fullCache.size > 12) fullCache.remove(fullCache.keys.first())
        result
    }

    /**
     * Downloads an attachment to `cacheDir/attachments` under a sanitized name.
     * Server-controlled filenames are reduced to a safe single path segment and the
     * resolved file is verified to stay inside the attachments directory; decoded
     * bytes are streamed with a hard cap so a huge part cannot OOM the app.
     */
    suspend fun downloadAttachment(meta: AttachmentMeta): File = withContext(Dispatchers.IO) {
        val att = api.getAttachment(meta.messageId, meta.attachmentId)
        val data = requireNotNull(att.data) { "Attachment ${meta.attachmentId} has no data" }
        require(att.size <= MAX_DOWNLOAD_BYTES) {
            "Attachment ${meta.filename} (${att.size / 1024 / 1024} MB) exceeds the " +
                "${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB limit"
        }
        val dir = File(appContext.cacheDir, "attachments").apply { mkdirs() }
        val name = SafePaths.childNameOr(
            raw = meta.filename,
            fallbackSeed = "attachment-${meta.attachmentId}",
            lastResort = "attachment-${Integer.toHexString(meta.attachmentId.hashCode())}",
        )
        val target = File(dir, name)
        check(SafePaths.isInside(dir, target)) { "Resolved attachment path escapes the attachments directory" }
        try {
            Base64.getUrlDecoder().wrap(data.byteInputStream()).use { decoded ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(COPY_BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        val n = decoded.read(buf)
                        if (n < 0) break
                        written += n
                        check(written <= MAX_DOWNLOAD_BYTES) { "Attachment exceeds the download size limit" }
                        output.write(buf, 0, n)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        target
    }

    suspend fun messageSnapshot(messageId: String): MessageEntity? =
        messageDao.get(accountEmail, messageId)

    // ------------------------------------------------------------------ actions

    suspend fun archive(threadIds: List<String>): Undoable {
        threadIds.forEach { api.modifyThread(it, ModifyLabelsRequest(removeLabelIds = listOf("INBOX"))) }
        threadIds.forEach { threadDao.setInInbox(accountEmail, it, false) }
        return Undoable("Archived") {
            threadIds.forEach {
                api.modifyThread(it, ModifyLabelsRequest(addLabelIds = listOf("INBOX")))
                threadDao.setInInbox(accountEmail, it, true)
            }
        }
    }

    suspend fun trash(threadIds: List<String>): Undoable {
        threadIds.forEach { api.trashThread(it) }
        threadIds.forEach { threadDao.setInInbox(accountEmail, it, false) }
        return Undoable("Moved to Trash") {
            threadIds.forEach {
                api.untrashThread(it)
                threadDao.setInInbox(accountEmail, it, true)
            }
        }
    }

    suspend fun toggleStar(threadId: String, starred: Boolean): Undoable {
        val req = if (starred) ModifyLabelsRequest(addLabelIds = listOf("STARRED"))
        else ModifyLabelsRequest(removeLabelIds = listOf("STARRED"))
        api.modifyThread(threadId, req)
        threadDao.setStarred(accountEmail, threadId, starred)
        return Undoable(if (starred) "Starred" else "Unstarred") { toggleStar(threadId, !starred) }
    }

    suspend fun markRead(threadIds: List<String>, read: Boolean): Undoable {
        val req = if (read) ModifyLabelsRequest(removeLabelIds = listOf("UNREAD"))
        else ModifyLabelsRequest(addLabelIds = listOf("UNREAD"))
        threadIds.forEach { api.modifyThread(it, req) }
        threadIds.forEach { threadDao.applyRead(accountEmail, it, read) }
        return Undoable(if (read) "Marked read" else "Marked unread") { markRead(threadIds, !read) }
    }

    suspend fun reportSpam(threadIds: List<String>): Undoable {
        threadIds.forEach {
            api.modifyThread(it, ModifyLabelsRequest(addLabelIds = listOf("SPAM"), removeLabelIds = listOf("INBOX")))
        }
        threadIds.forEach { threadDao.setInInbox(accountEmail, it, false) }
        return Undoable("Reported spam") {
            threadIds.forEach {
                api.modifyThread(it, ModifyLabelsRequest(removeLabelIds = listOf("SPAM"), addLabelIds = listOf("INBOX")))
                threadDao.setInInbox(accountEmail, it, true)
            }
        }
    }

    suspend fun applyLabel(labelId: String, add: Boolean, threadIds: List<String>): Undoable {
        val req = if (add) ModifyLabelsRequest(addLabelIds = listOf(labelId))
        else ModifyLabelsRequest(removeLabelIds = listOf(labelId))
        threadIds.forEach { api.modifyThread(it, req) }
        // Refresh aggregates so list filters stay truthful.
        hydrateThreads(threadIds)
        return Undoable(if (add) "Label applied" else "Label removed") { applyLabel(labelId, !add, threadIds) }
    }

    /**
     * Local-only snooze: INBOX is removed server-side so the mail leaves every client's inbox;
     * our SnoozeWorker re-adds INBOX at [wakeAt]. The wake time is persisted in `snooze_wakes`
     * (source of truth) so it survives WorkManager input-data loss; the WorkRequest input data
     * is only a fast path.
     */
    suspend fun snooze(threadId: String, wakeAt: Long): Undoable {
        threadDao.setSnoozed(accountEmail, threadId, wakeAt)
        api.modifyThread(threadId, ModifyLabelsRequest(removeLabelIds = listOf("INBOX")))
        threadDao.setInInbox(accountEmail, threadId, false)
        wakeDao.upsert(SnoozeWakeEntity(accountEmail = accountEmail, threadId = threadId, targetAt = wakeAt))
        scheduleWake(threadId, wakeAt)
        return Undoable("Snoozed") { unsnooze(threadId) }
    }

    /**
     * Real unsnooze: cancels the wake work and row, restores INBOX locally first (immediate
     * UI feedback), then server-side. On server failure the local state and wake row are
     * rolled back and the exception propagates so callers can surface it.
     */
    suspend fun unsnooze(threadId: String) {
        workManager.cancelUniqueWork(SnoozeWorker.workName(accountEmail, threadId))
        restoreFromSnooze(threadId)
    }

    /**
     * Shared restore path for manual unsnooze and the SnoozeWorker (which must NOT cancel
     * its own running work). Clears the wake row and snooze state, restores INBOX locally
     * first, then server-side; rolls everything back if the server call fails.
     */
    suspend fun restoreFromSnooze(threadId: String) {
        val before = threadDao.get(accountEmail, threadId)
        wakeDao.delete(accountEmail, threadId)
        threadDao.setSnoozed(accountEmail, threadId, null)
        threadDao.setInInbox(accountEmail, threadId, true)
        try {
            api.modifyThread(threadId, ModifyLabelsRequest(addLabelIds = listOf("INBOX")))
        } catch (t: Throwable) {
            Log.w(TAG, "Unsnooze server call failed for $threadId — rolling back", t)
            before?.let {
                threadDao.setSnoozed(accountEmail, threadId, it.snoozedUntil)
                threadDao.setInInbox(accountEmail, threadId, it.inInbox)
                if (it.snoozedUntil != null) {
                    wakeDao.upsert(
                        SnoozeWakeEntity(accountEmail = accountEmail, threadId = threadId, targetAt = it.snoozedUntil),
                    )
                    scheduleWake(threadId, it.snoozedUntil)
                }
            }
            throw t
        }
    }

    private fun scheduleWake(threadId: String, wakeAt: Long) {
        val delay = (wakeAt - System.currentTimeMillis()).coerceAtLeast(0)
        workManager.enqueueUniqueWork(
            SnoozeWorker.workName(accountEmail, threadId),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SnoozeWorker>()
                // Convenience only — the worker falls back to the snooze_wakes table.
                .setInputData(workDataOf(SnoozeWorker.KEY_ACCOUNT to accountEmail, SnoozeWorker.KEY_THREAD_ID to threadId))
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(SnoozeWorker.WORK_TAG)
                .build(),
        )
    }

    // ------------------------------------------------------------------ sending

    /**
     * Enqueues a send through the local outbox. The actual API call is delayed by the undo
     * window — cancelling within it is Gmail-style "Undo send". For scheduled sends the delay
     * runs until [scheduledAt].
     * Returns the outbox row id (used by undo).
     */
    suspend fun enqueueSend(request: ComposeRequest, scheduledAt: Long? = null): Long = withContext(Dispatchers.IO) {
        // MimeComposer enforces MAX_TOTAL_ATTACHMENT_BYTES at compose time.
        val raw = MimeComposer.compose(
            from = accountEmail,
            to = request.to,
            cc = request.cc,
            bcc = request.bcc,
            subject = request.subject,
            bodyText = request.bodyText,
            inReplyToMessageId = request.inReplyToMessageId,
            referencesHeader = request.referencesHeader,
            attachments = request.attachmentFiles.map { MimeComposer.Attachment(it, guessMime(it.name)) },
        )
        val bytes = Base64.getUrlDecoder().decode(raw)
        val targetAt = scheduledAt ?: (System.currentTimeMillis() + settings.undoSeconds() * 1000L)
        val kind = if (scheduledAt == null) OutboxKind.SEND else OutboxKind.SCHEDULED_SEND
        val id = outboxDao.insert(
            OutboxEntity(
                accountEmail = accountEmail,
                kind = kind.name,
                threadId = request.threadId,
                rfc822Base64 = null, // payloads live on disk now; legacy column stays for migrated rows
                subject = request.subject,
                targetAt = targetAt,
            ),
        )
        val relativePath = try {
            OutboxFiles.writeNew(appContext.filesDir, id, bytes)
        } catch (t: Throwable) {
            outboxDao.delete(id)
            throw t
        }
        outboxDao.setPayload(id, relativePath, bytes.size.toLong())
        val delay = (targetAt - System.currentTimeMillis()).coerceAtLeast(0)
        workManager.enqueueUniqueWork(
            OutboxWorker.workName(id),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxWorker>()
                .setInputData(workDataOf(OutboxWorker.KEY_OUTBOX_ID to id))
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("outbox")
                .build(),
        )
        id
    }

    /** Undo-send / cancel scheduled send: cancel the pending worker and drop the queued row. */
    suspend fun cancelQueuedSend(outboxId: Long) {
        workManager.cancelUniqueWork(OutboxWorker.workName(outboxId))
        outboxDao.setState(outboxId, OutboxState.CANCELLED.name)
        outboxDao.get(outboxId)?.let { entry ->
            OutboxFiles.deletePayloadFile(appContext.filesDir, entry.path, entry.id)
        }
        outboxDao.delete(outboxId)
    }

    /** Count of sends stuck in FAILED state — surfaced for the mailbox failed-send banner. */
    fun observeFailedSends(): Flow<Int> = outboxDao.observeFailedCount(accountEmail)

    suspend fun archiveAfterSend(threadId: String?) {
        threadId ?: return
        runCatching { archive(listOf(threadId)) }
    }

    // ------------------------------------------------------------------ search

    /** Server-side search with full Gmail operator syntax (from:, after:, has:attachment …). */
    suspend fun searchServer(query: String): List<ThreadEntity> = withContext(Dispatchers.IO) {
        val lowered = query.lowercase()
        val page = api.listThreads(
            q = query,
            maxResults = 25,
            includeSpamTrash = "in:trash" in lowered || "in:spam" in lowered,
        )
        hydrateThreads(page.threads.map { it.id })
        page.threads.mapNotNull { threadDao.get(accountEmail, it.id) }
    }

    suspend fun searchLocal(query: String): List<ThreadEntity> = withContext(Dispatchers.IO) {
        val sanitized = query.replace("\"", "").trim()
        if (sanitized.isEmpty()) return@withContext emptyList()
        val hits = messageDao.searchLocal(accountEmail, "$sanitized*")
        hits.groupBy { it.threadId }.map { (threadId, msgs) ->
            val latest = msgs.maxByOrNull { it.date }!!
            ThreadEntity(
                accountEmail = accountEmail,
                id = threadId,
                snippet = latest.snippet,
                subject = latest.subject,
                fromAddress = latest.fromAddress,
                fromName = AddressUtils.split(latest.fromAddress).first,
                date = latest.date,
                messageCount = msgs.size,
                unreadCount = msgs.count { !it.read },
                hasAttachments = msgs.any { it.hasAttachments },
                starred = msgs.any { it.starred },
                inInbox = false,
                labelsCsv = latest.labelsCsv,
            )
        }.sortedByDescending { it.date }
    }

    // ------------------------------------------------------------------ mapping helpers

    private suspend fun toThreadEntity(thread: Thread): ThreadEntity {
        val msgs = thread.messages
        val latest = msgs.maxByOrNull { it.internalDate?.toLongOrNull() ?: 0L }
            ?: Message(id = thread.id, threadId = thread.id)
        val labelsUnion = msgs.flatMapTo(LinkedHashSet()) { it.labelIds }
        val (name, _) = AddressUtils.split(header(latest, "From"))
        return ThreadEntity(
            accountEmail = accountEmail,
            id = thread.id,
            snippet = thread.snippet ?: latest.snippet.orEmpty(),
            subject = header(latest, "Subject").ifBlank { "(no subject)" },
            fromAddress = header(latest, "From"),
            fromName = name,
            date = latest.internalDate?.toLongOrNull() ?: 0L,
            messageCount = msgs.size,
            unreadCount = msgs.count { it.labelIds.contains("UNREAD") },
            hasAttachments = msgs.any { hasAttachment(it) },
            starred = labelsUnion.contains("STARRED"),
            inInbox = latest.labelIds.contains("INBOX"),
            categories = latest.labelIds.filter { it.startsWith("CATEGORY_") }.joinToString(","),
            labelsCsv = labelsUnion.joinToString(","),
            snoozedUntil = threadDao.get(accountEmail, thread.id)?.snoozedUntil,
        )
    }

    private fun toMessageEntity(m: Message, fallbackThreadId: String): MessageEntity = MessageEntity(
        accountEmail = accountEmail,
        id = m.id,
        threadId = m.threadId ?: fallbackThreadId,
        subject = header(m, "Subject").ifBlank { "(no subject)" },
        fromAddress = header(m, "From"),
        toCsv = header(m, "To"),
        ccCsv = header(m, "Cc"),
        date = m.internalDate?.toLongOrNull() ?: 0L,
        snippet = m.snippet.orEmpty(),
        read = !m.labelIds.contains("UNREAD"),
        starred = m.labelIds.contains("STARRED"),
        hasAttachments = hasAttachment(m),
        labelsCsv = m.labelIds.joinToString(","),
        messageIdHeader = header(m, "Message-ID").ifEmpty { header(m, "Message-Id") },
    )

    private fun hasAttachment(m: Message): Boolean =
        m.payload?.let { p -> !p.filename.isNullOrBlank() || p.parts.any { !it.filename.isNullOrBlank() } } == true

    private fun header(m: Message, name: String): String =
        m.payload?.headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value.orEmpty()

    private fun Message.findBody(mime: String): dev.xxemail.data.api.MessagePart? {
        fun walk(part: dev.xxemail.data.api.MessagePart): dev.xxemail.data.api.MessagePart? {
            if (part.mimeType == mime && part.body?.data != null) return part
            part.parts.forEach { walk(it)?.let { hit -> return hit } }
            return null
        }
        return payload?.let(::walk)
    }

    private fun Message.attachments(): List<AttachmentMeta> {
        val out = mutableListOf<AttachmentMeta>()
        fun walk(part: dev.xxemail.data.api.MessagePart) {
            val body = part.body
            if (!part.filename.isNullOrBlank() && body?.attachmentId != null) {
                out += AttachmentMeta(id, body.attachmentId!!, part.filename!!, part.mimeType.orEmpty(), body.size)
            }
            part.parts.forEach(::walk)
        }
        payload?.let(::walk)
        return out
    }

    private fun decodeBase64Url(data: String): String = try {
        String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        // Some payloads arrive standard-encoded; fall back gracefully.
        String(Base64.getMimeDecoder().decode(data), Charsets.UTF_8)
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".html", true) -> "text/html"
        name.endsWith(".zip", true) -> "application/zip"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "MailRepository"
        private const val MAX_THREADS_PER_DELTA = 60

        /** Decoded-attachment memory cap (reuses the compose-time attachment budget). */
        val MAX_DOWNLOAD_BYTES: Long = MimeComposer.MAX_TOTAL_ATTACHMENT_BYTES
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
