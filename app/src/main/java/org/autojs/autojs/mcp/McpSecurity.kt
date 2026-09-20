package org.autojs.autojs.mcp

import org.autojs.autojs.core.pref.Pref
import java.security.SecureRandom

/**
 * Access token handling for the MCP server.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Security
 *  ! The token is the only thing standing between a reachable port and full
 *  ! control of the device, so it is always required, never optional, and is
 *  ! compared in constant time.
 *  ! zh-CN: 令牌是"端口可达"与"设备被完全控制"之间的唯一屏障,
 *  ! 因此始终强制校验, 不可选, 且采用常量时间比较.
 */
object McpSecurity {

    private const val TOKEN_BYTE_LENGTH = 24
    private const val BEARER_PREFIX = "Bearer"

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private val random by lazy { SecureRandom() }

    /**
     * Returns the persisted token, generating and persisting one on first use.
     * zh-CN: 返回已持久化的令牌, 首次使用时生成并持久化.
     */
    @JvmStatic
    fun accessToken(): String {
        Pref.mcpServerTokenOrNull?.let { return it }
        return generateToken().also { Pref.putMcpServerToken(it) }
    }

    /**
     * Generates a fresh token and persists it, invalidating all existing clients.
     * zh-CN: 生成并持久化新令牌, 使所有现有客户端立即失效.
     */
    @JvmStatic
    fun resetToken(): String = generateToken().also { Pref.putMcpServerToken(it) }

    @JvmStatic
    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTE_LENGTH)
        random.nextBytes(bytes)
        val sb = StringBuilder(TOKEN_BYTE_LENGTH)
        for (b in bytes) {
            sb.append(ALPHABET[(b.toInt() and 0xFF) % ALPHABET.length])
        }
        return sb.toString()
    }

    /**
     * Validates an `Authorization` header value against the persisted token.
     * zh-CN: 校验 `Authorization` 请求头的值与已持久化令牌是否一致.
     */
    @JvmStatic
    fun isAuthorizationValid(header: String?): Boolean {
        val raw = header?.trim() ?: return false
        if (!raw.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
            return false
        }
        val presented = raw.substring(BEARER_PREFIX.length).trim()
        if (presented.isEmpty()) {
            return false
        }
        return constantTimeEquals(accessToken(), presented)
    }

    /**
     * Compares two strings without leaking length or content through timing.
     * zh-CN: 以常量时间比较两个字符串, 避免通过耗时泄漏长度或内容.
     */
    private fun constantTimeEquals(expected: String, presented: String): Boolean {
        val a = expected.toByteArray(Charsets.UTF_8)
        val b = presented.toByteArray(Charsets.UTF_8)
        // Fold the length difference into the result instead of returning early.
        // zh-CN: 将长度差异并入结果, 而不是提前返回.
        var diff = a.size xor b.size
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i % b.size].toInt())
        }
        return diff == 0
    }

}
