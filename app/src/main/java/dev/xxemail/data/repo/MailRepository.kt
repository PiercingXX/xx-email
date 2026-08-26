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
import dev.xxemail.data.api.HistoryItem
import dev.xxemail.data.api.Message
import dev.xxemail.data.api.MessageParts
import dev.xxemail.data.api.MimeComposer
import dev.xxemail.data.api.ModifyLabelsRequest
import dev.xxemail.data.api.ThreadAggregation
import dev.xxemail.data.db.AccountDao
import dev.xxemail.data.db.AccountEntity
import dev.xxemail.data.db.FolderPageDao
import dev.xxemail.data.db.FolderPageEntity
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
import dev.xxemail.domain.LabelCsv
import dev.xxemail.domain.MailboxFolder
import dev.xxemail.domain.SafePaths
import dev.xxemail.sync.NewMailDetector
import dev.xxemail.sync.OutboxFiles
import dev.xxemail.sync.OutboxWorker
import dev.xxemail.sync.SnoozeWorker
import dev.xxemail.sync.SyncScheduler
import dev.xxemail.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val folderPageDao: FolderPageDao,
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

            val storedHistoryId = existing?.historyId
            val endHistoryId = profile.historyId
            // Full passes (first sync of an account, forced rebuild, missing end id) see the
            // whole mailbox at once — snapshot-less ⇒ NewMailDetector suppresses notifications.
            val fullPass = storedHistoryId == null || forceFull || endHistoryId == null
            val knownInboxIdsBefore = if (fullPass) null else threadDao.inboxAll(accountEmail).map { it.id }.toSet()
            if (fullPass) {
                initialSync()
                accountDao.updateSyncPoint(accountEmail, endHistoryId, System.currentTimeMillis())
            } else {
                deltaSync(storedHistoryId!!, endHistoryId)
            }

            val unreadNow = threadDao.inboxAll(accountEmail).filter { it.unreadCount > 0 }
            Result.success(SyncResult(NewMailDetector.newArrivals(knownInboxIdsBefore, unreadNow)))
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

    /** Pull recent INBOX pages (bounded — keeps first-sync quota sane); saves the continuation token for "load more". */
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
        saveFolderPage(INBOX_PAGE_KEY, pageToken)
    }

    private suspend fun saveFolderPage(key: String, token: String?) {
        folderPageDao.upsert(FolderPageEntity(accountEmail = accountEmail, folderKey = key, nextPageToken = token))
    }

    /**
     * Delta sync via history.list (2 quota units). Affected threads are rebuilt wholesale
     * from threads.get (10 units each, capped per pass; overflow stops paging so the
     * checkpoint only advances past fully hydrated items and the overflow window is
     * re-walked by an immediate follow-up sync — never silently skipped).
     * HTTP 404 ⇒ stored historyId expired ⇒ bounded full resync.
     *
     * History ids are opaque uint64 strings from Gmail — they are only ever compared or
     * persisted verbatim, never arithmetically.
     */
    private suspend fun deltaSync(startHistoryId: String, fallbackEnd: String?) {
        val touchedThreadIds = LinkedHashSet<String>()
        val messageIdsNeedingLookup = LinkedHashSet<String>()
        val deletedMessageIds = LinkedHashSet<String>()
        var lastFullyProcessedId = startHistoryId
        var sawAnyItem = false
        var sawOverflow = false
        var pageToken: String? = null

        try {
            paging@ while (true) {
                val page = api.listHistory(startHistoryId = startHistoryId, pageToken = pageToken)
                for (item in page.history) {
                    sawAnyItem = true
                    collectHistoryRefs(item, touchedThreadIds, messageIdsNeedingLookup, deletedMessageIds)
                    if (touchedThreadIds.size + messageIdsNeedingLookup.size > MAX_THREADS_PER_DELTA) {
                        // More work than one pass can hydrate. This item's contribution stays
                        // unprocessed: the checkpoint below stops at the previous item, so the
                        // follow-up sync re-walks it instead of skipping the overflow window.
                        sawOverflow = true
                        break@paging
                    }
                    lastFullyProcessedId = item.id
                }
                pageToken = page.nextPageToken ?: break
            }
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
        val checkpoint = if (sawAnyItem) lastFullyProcessedId else fallbackEnd ?: startHistoryId
        accountDao.updateSyncPoint(accountEmail, checkpoint, System.currentTimeMillis())
        if (sawOverflow) scheduleFollowUpDelta()
    }

    /**
     * Overflow pass: drain the remaining history window right away instead of waiting up
     * to a full polling interval. The next run resumes exactly at the persisted checkpoint;
     * REPLACE keeps at most one follow-up queued.
     */
    private fun scheduleFollowUpDelta() {
        workManager.enqueueUniqueWork(
            SyncScheduler.UNIQUE_ONESHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Gmail sometimes fills only `messages` without the typed lists — walk every shape. */
    private fun collectHistoryRefs(
        item: HistoryItem,
        touchedThreadIds: MutableSet<String>,
        messageIdsNeedingLookup: MutableSet<String>,
        deletedMessageIds: MutableSet<String>,
    ) {
        fun handleRef(refMessage: dev.xxemail.data.api.MessageRef?) {
            refMessage?.threadId?.let(touchedThreadIds::add)
                ?: refMessage?.id?.let(messageIdsNeedingLookup::add)
        }
        item.messages.forEach { handleRef(it.message) }
        item.messagesAdded.forEach { handleRef(it.message) }
        item.labelsAdded.forEach { handleRef(it.message) }
        item.labelsRemoved.forEach { handleRef(it.message) }
        item.messagesDeleted.forEach { removed -> removed.message?.id?.let(deletedMessageIds::add) }
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
                    threadRows += ThreadAggregation.build(accountEmail, full, threadDao.get(accountEmail, full.id)?.snoozedUntil)
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
                // Judge by THIS category's own count — a populated inbox must not starve an empty tab.
                if (threadDao.countInboxCategory(accountEmail, folder.category.orEmpty(), folder.includeEmptyPrimary) == 0) {
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

    // ------------------------------------------------------------------ load more

    /** Server list params for a folder; null ⇒ local-only view (no paging). */
    private fun folderListQuery(folder: MailboxFolder): Pair<List<String>?, Boolean>? = when {
        folder.category != null -> listOf("INBOX", folder.category!!) to false
        folder.labelId != null -> listOf(folder.labelId!!) to (folder.labelId == "TRASH" || folder.labelId == "SPAM")
        folder == MailboxFolder.ALL_MAIL -> null to false
        else -> null
    }

    /** Inbox tabs share one underlying INBOX listing → one cursor for all of them. */
    private fun folderPageKey(folder: MailboxFolder): String? = when {
        folder.category != null -> INBOX_PAGE_KEY
        folder.labelId != null -> folder.labelId
        folder == MailboxFolder.ALL_MAIL -> folder.name
        else -> null
    }

    suspend fun hasMorePages(folder: MailboxFolder): Boolean = withContext(Dispatchers.IO) {
        val key = folderPageKey(folder) ?: return@withContext false
        folderPageDao.get(accountEmail, key)?.nextPageToken != null
    }

    /** Fetches the next page from the stored cursor and persists the new one; true when more remain. */
    suspend fun loadMore(folder: MailboxFolder): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val key = folderPageKey(folder) ?: return@runCatching false
            val token = folderPageDao.get(accountEmail, key)?.nextPageToken ?: return@runCatching false
            val (labelIds, spamTrash) = folderListQuery(folder)!!
            val page = api.listThreads(
                labelIds = labelIds,
                maxResults = PAGE_SIZE,
                pageToken = token,
                includeSpamTrash = spamTrash,
            )
            hydrateThreads(page.threads.map { it.id })
            saveFolderPage(key, page.nextPageToken)
            page.nextPageToken != null
        }.getOrElse {
            Log.w(TAG, "loadMore(${folder.name}) failed for $accountEmail", it)
            false
        }
    }

    // ------------------------------------------------------------------ reading

    @Serializable
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
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetches full bodies for a thread (lazy — sync stores metadata only). */
    suspend fun loadFullThread(threadId: String): List<FullMessage> = withContext(Dispatchers.IO) {
        fullCache[threadId]?.let { return@withContext it }
        val result = messageDao.listForThread(accountEmail, threadId).map { row ->
            if (row.bodyFetched) {
                FullMessage(row, row.bodyHtml, row.bodyPlain, decodeAttachments(row))
            } else {
                val full = runCatching { api.getMessage(row.id, format = "full") }.getOrNull()
                val payload = full?.payload
                if (payload == null) {
                    // Fetch failed or response unusable: leave bodyFetched = false so the next
                    // open retries instead of permanently caching an empty body.
                    FullMessage(row, row.bodyHtml, row.bodyPlain, emptyList())
                } else {
                    val html = MessageParts.findBody(payload, "text/html")?.body?.data?.let(::decodeBase64Url)
                    val plain = MessageParts.findBody(payload, "text/plain")?.body?.data?.let(::decodeBase64Url)
                    val atts = MessageParts.attachments(row.id, payload).map {
                        AttachmentMeta(it.messageId, it.attachmentId, it.filename, it.mimeType, it.size)
                    }
                    val attJson = runCatching { json.encodeToString(atts) }.getOrNull()
                    messageDao.updateBodies(accountEmail, row.id, html, plain, attJson)
                    FullMessage(row.copy(bodyHtml = html, bodyPlain = plain, bodyFetched = true, attachmentsJson = attJson), html, plain, atts)
                }
            }
        }
        // Only cache fully-fetched threads; failed fetches must stay retryable.
        if (result.all { it.entity.bodyFetched }) {
            fullCache[threadId] = result
            while (fullCache.size > 12) fullCache.remove(fullCache.keys.first())
        }
        result
    }

    private fun decodeAttachments(row: MessageEntity): List<AttachmentMeta> =
        row.attachmentsJson?.takeIf { it.isNotBlank() }
            ?.let { src -> runCatching { json.decodeFromString<List<AttachmentMeta>>(src) }.getOrNull() }
            .orEmpty()

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

    /**
     * Local-first aggregate update so folders move immediately, before any hydrate.
     * Never skipped, even when the server call later fails — the next delta sync
     * reconciles any divergence.
     */
    private suspend fun mutateLocal(threadIds: List<String>, transform: (ThreadEntity) -> ThreadEntity) {
        val rows = threadIds.mapNotNull { threadDao.get(accountEmail, it) }
        if (rows.isNotEmpty()) threadDao.upsertAll(rows.map(transform))
    }

    suspend fun archive(threadIds: List<String>): Undoable {
        mutateLocal(threadIds) {
            it.copy(inInbox = false, labelsCsv = LabelCsv.remove(it.labelsCsv, "INBOX"))
        }
        threadIds.forEach { api.modifyThread(it, ModifyLabelsRequest(removeLabelIds = listOf("INBOX"))) }
        return Undoable("Archived") {
            threadIds.forEach {
                api.modifyThread(it, ModifyLabelsRequest(addLabelIds = listOf("INBOX")))
            }
            mutateLocal(threadIds) {
                it.copy(inInbox = true, labelsCsv = LabelCsv.add(it.labelsCsv, "INBOX"))
            }
        }
    }

    suspend fun trash(threadIds: List<String>): Undoable {
        val before = threadIds.mapNotNull { threadDao.get(accountEmail, it) }
        mutateLocal(threadIds) {
            it.copy(
                inInbox = false,
                labelsCsv = LabelCsv.add(LabelCsv.remove(it.labelsCsv, "INBOX"), "TRASH"),
            )
        }
        threadIds.forEach { api.trashThread(it) }
        return Undoable("Moved to Trash") {
            threadIds.forEach { api.untrashThread(it) }
            // Reverse both sides verbatim: prior Inbox membership and prior label set.
            before.forEach { row ->
                threadDao.setInboxAndLabels(accountEmail, row.id, row.inInbox, row.labelsCsv)
            }
        }
    }

    suspend fun toggleStar(threadId: String, starred: Boolean): Undoable {
        val req = if (starred) ModifyLabelsRequest(addLabelIds = listOf("STARRED"))
        else ModifyLabelsRequest(removeLabelIds = listOf("STARRED"))
        mutateLocal(listOf(threadId)) {
            it.copy(
                starred = starred,
                labelsCsv = if (starred) LabelCsv.add(it.labelsCsv, "STARRED") else LabelCsv.remove(it.labelsCsv, "STARRED"),
            )
        }
        api.modifyThread(threadId, req)
        return Undoable(if (starred) "Starred" else "Unstarred") { toggleStar(threadId, !starred) }
    }

    suspend fun markRead(threadIds: List<String>, read: Boolean): Undoable {
        val req = if (read) ModifyLabelsRequest(removeLabelIds = listOf("UNREAD"))
        else ModifyLabelsRequest(addLabelIds = listOf("UNREAD"))
        mutateLocal(threadIds) {
            it.copy(
                unreadCount = if (read) 0 else maxOf(it.unreadCount, 1),
                labelsCsv = if (read) LabelCsv.remove(it.labelsCsv, "UNREAD") else LabelCsv.add(it.labelsCsv, "UNREAD"),
            )
        }
        threadIds.forEach { api.modifyThread(it, req) }
        return Undoable(if (read) "Marked read" else "Marked unread") { markRead(threadIds, !read) }
    }

    suspend fun reportSpam(threadIds: List<String>): Undoable {
        mutateLocal(threadIds) {
            it.copy(
                inInbox = false,
                labelsCsv = LabelCsv.add(LabelCsv.remove(it.labelsCsv, "INBOX"), "SPAM"),
            )
        }
        threadIds.forEach {
            api.modifyThread(it, ModifyLabelsRequest(addLabelIds = listOf("SPAM"), removeLabelIds = listOf("INBOX")))
        }
        return Undoable("Reported spam") {
            threadIds.forEach {
                api.modifyThread(it, ModifyLabelsRequest(removeLabelIds = listOf("SPAM"), addLabelIds = listOf("INBOX")))
            }
            mutateLocal(threadIds) {
                it.copy(
                    inInbox = true,
                    labelsCsv = LabelCsv.add(LabelCsv.remove(it.labelsCsv, "SPAM"), "INBOX"),
                )
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
        mutateLocal(listOf(threadId)) {
            it.copy(inInbox = false, labelsCsv = LabelCsv.remove(it.labelsCsv, "INBOX"))
        }
        api.modifyThread(threadId, ModifyLabelsRequest(removeLabelIds = listOf("INBOX")))
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
        threadDao.setInboxAndLabels(
            accountEmail,
            threadId,
            true,
            LabelCsv.add(before?.labelsCsv.orEmpty(), "INBOX"),
        )
        try {
            api.modifyThread(threadId, ModifyLabelsRequest(addLabelIds = listOf("INBOX")))
        } catch (t: Throwable) {
            Log.w(TAG, "Unsnooze server call failed for $threadId — rolling back", t)
            before?.let {
                threadDao.setSnoozed(accountEmail, threadId, it.snoozedUntil)
                threadDao.setInboxAndLabels(accountEmail, threadId, it.inInbox, it.labelsCsv)
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
        hasAttachments = MessageParts.hasAttachment(m),
        labelsCsv = m.labelIds.joinToString(","),
        messageIdHeader = header(m, "Message-ID").ifEmpty { header(m, "Message-Id") },
    )

    private fun header(m: Message, name: String): String =
        m.payload?.headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value.orEmpty()

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
        /** Page-key shared by all inbox category tabs (one INBOX listing, one cursor). */
        private const val INBOX_PAGE_KEY = "INBOX"
        private const val PAGE_SIZE = 50

        /** Decoded-attachment memory cap (reuses the compose-time attachment budget). */
        val MAX_DOWNLOAD_BYTES: Long = MimeComposer.MAX_TOTAL_ATTACHMENT_BYTES
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
