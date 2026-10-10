package app.rev.data.remote

import kotlinx.serialization.Serializable

// Wire types mirroring shared/schemas (@rev/schemas). Keep in sync with the server.

@Serializable
internal data class HealthDto(
    val status: String,
    val name: String,
    val version: String,
    val instanceId: String,
)

@Serializable
internal data class LoginRequestDto(
    val username: String,
    val password: String,
    val deviceName: String? = null,
)

@Serializable
internal data class RefreshRequestDto(val refreshToken: String)

@Serializable
internal data class UserDto(val id: Int, val username: String)

@Serializable
internal data class TokenResponseDto(
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val refreshTokenExpiresAt: Long,
    val user: UserDto,
)

@Serializable
internal data class ApiErrorDto(val error: Body) {
    @Serializable
    data class Body(val code: String, val message: String)
}
