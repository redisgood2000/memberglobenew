package com.memberglobe.nativeapp

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Models and routes verified against the existing Member Globe AppDeploy backend source.
 * A valid AppDeploy bearer token is still required for every endpoint below.
 */
data class MemberResponse(val members: List<MemberDto> = emptyList())
data class MemberDto(
    val id: String,
    val name: String = "Member",
    val email: String = "",
    val picture: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val city: String = "",
    val country: String = "",
    val address: String = "",
    val sharing: Boolean = false,
    val updatedAt: Long? = null,
    val bio: String = ""
)

data class ShareLocationRequest(val lat: Double, val lng: Double, val address: String = "")
data class OkResponse(val ok: Boolean = false)
data class ChatMessagesResponse(val messages: List<ChatMessageDto> = emptyList())
data class ChatMessageDto(
    val id: String? = null,
    val name: String = "Member",
    val text: String = "",
    val createdAt: Long = 0L
)
data class SendChatMessageRequest(val text: String)
data class SendChatMessageResponse(val message: ChatMessageDto)
data class NotificationStatusResponse(val enabled: Boolean = false)
data class NotificationSubscribeResponse(val enabled: Boolean = false)

/** Supplies an access token obtained through the supported native authentication flow. */
fun interface AccessTokenProvider {
    suspend fun getAccessToken(): String?
}

class AppDeployBearerInterceptor(
    private val tokenProvider: AccessTokenProvider
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // OkHttp interceptors are synchronous. The application must provide a token cache
        // populated by the native auth flow before calling authenticated endpoints.
        val token = NativeTokenCache.accessToken
        val request = chain.request().newBuilder().apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        return chain.proceed(request)
    }
}

/**
 * Short-lived in-memory token cache only. Do not persist tokens in plain preferences.
 * Populate after implementing and verifying the AppDeploy native OAuth/PKCE flow.
 */
object NativeTokenCache {
    @Volatile var accessToken: String? = null
}

interface MemberGlobeApi {
    @GET("api/members")
    suspend fun getMembers(): MemberResponse

    @POST("api/location/share")
    suspend fun shareLocation(@Body request: ShareLocationRequest): Map<String, Any?>

    @POST("api/location/stop")
    suspend fun stopLocationSharing(): OkResponse

    @GET("api/chat/messages")
    suspend fun getChatMessages(): ChatMessagesResponse

    @POST("api/chat/messages")
    suspend fun sendChatMessage(@Body request: SendChatMessageRequest): SendChatMessageResponse

    @GET("api/notifications/chat/status")
    suspend fun getChatNotificationStatus(): NotificationStatusResponse

    @POST("api/notifications/chat/subscribe")
    suspend fun subscribeChatNotifications(): NotificationSubscribeResponse

    @POST("api/notifications/chat/unsubscribe")
    suspend fun unsubscribeChatNotifications(): NotificationSubscribeResponse
}

object MemberGlobeApiFactory {
    // Existing Member Globe web app origin; backend routes are under /api/.
    private const val BASE_URL = "https://member-globe-t5uo18.v2.appdeploy.ai/"

    fun create(): MemberGlobeApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val token = NativeTokenCache.accessToken
                val request = chain.request().newBuilder().apply {
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }.build()
                chain.proceed(request)
            })
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MemberGlobeApi::class.java)
    }
}
