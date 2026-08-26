package dev.xxemail.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Minimal Gmail REST surface used by xx-email. All calls require gmail.modify scope. */
interface GmailApi {

    @GET("gmail/v1/users/me/profile")
    suspend fun getProfile(): Profile

    @GET("gmail/v1/users/me/labels")
    suspend fun listLabels(): LabelListResponse

    @GET("gmail/v1/users/me/threads")
    suspend fun listThreads(
        @Query("q") q: String? = null,
        @Query("labelIds") labelIds: List<String>? = null,
        @Query("maxResults") maxResults: Int = 50,
        @Query("pageToken") pageToken: String? = null,
        @Query("includeSpamTrash") includeSpamTrash: Boolean = false,
    ): ThreadListResponse

    @GET("gmail/v1/users/me/threads/{id}")
    suspend fun getThread(
        @Path("id") id: String,
        @Query("format") format: String = "metadata",
    ): Thread

    @POST("gmail/v1/users/me/threads/{id}/modify")
    suspend fun modifyThread(@Path("id") id: String, @Body body: ModifyLabelsRequest): Thread

    @POST("gmail/v1/users/me/threads/{id}/trash")
    suspend fun trashThread(@Path("id") id: String): Thread

    @POST("gmail/v1/users/me/threads/{id}/untrash")
    suspend fun untrashThread(@Path("id") id: String): Thread

    @GET("gmail/v1/users/me/messages/{id}")
    suspend fun getMessage(
        @Path("id") id: String,
        @Query("format") format: String = "full",
    ): Message

    @POST("gmail/v1/users/me/messages/{id}/modify")
    suspend fun modifyMessage(@Path("id") id: String, @Body body: ModifyLabelsRequest): Message

    /** RFC822 upload. Body Content-Type must be message/rfc822 (uploadType=media). */
    @POST("upload/gmail/v1/users/me/messages/send?uploadType=media")
    suspend fun sendRaw(@Body body: okhttp3.RequestBody): Message

    @GET("gmail/v1/users/me/messages/{messageId}/attachments/{attachmentId}")
    suspend fun getAttachment(
        @Path("messageId") messageId: String,
        @Path("attachmentId") attachmentId: String,
    ): Attachment

    @GET("gmail/v1/users/me/history")
    suspend fun listHistory(
        @Query("startHistoryId") startHistoryId: String,
        @Query("maxResults") maxResults: Int = 100,
        @Query("pageToken") pageToken: String? = null,
    ): HistoryResponse

    @GET("gmail/v1/users/me/settings/vacation")
    suspend fun getVacation(): VacationSettings

    @GET("gmail/v1/users/me/settings/sendAs")
    suspend fun listSendAs(): SendAsListResponse
}
