package com.rootforsree.webshell.server

import java.security.SecureRandom
import java.util.Base64

/**
 * One random token per server start. The token is the only auth the web
 * shell has, so it must be unguessable and fresh on every start: a
 * stale token from a previous run never opens the new server.
 */
object TokenGenerator {
    private const val TOKEN_BYTES = 24

    /** 32 URL-safe characters, no padding, no characters that need escaping in a query string. */
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
