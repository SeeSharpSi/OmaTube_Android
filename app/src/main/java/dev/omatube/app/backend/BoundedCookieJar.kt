package dev.omatube.app.backend

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * In-memory, bounded cookie store for the extraction HTTP client.
 *
 * YouTube sets session cookies on redirects (for example the consent/visitor flow). Without a
 * [CookieJar] OkHttp drops `Set-Cookie` on redirects, so the follow-up request can lose state.
 *
 * Properties:
 *  - process-local only: nothing is ever written to disk, SharedPreferences or the database;
 *  - bounded: at most [maxCookies] cookies and [maxBytes] of `name+value` data, oldest evicted
 *    first (insertion order, re-saved cookies move to the end);
 *  - thread-safe;
 *  - expired cookies are dropped lazily.
 *
 * It deliberately never logs cookie values.
 */
class BoundedCookieJar(
    private val maxCookies: Int = DEFAULT_MAX_COOKIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : CookieJar {

    private val lock = Any()
    private val cookies = LinkedHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            for (cookie in cookies) {
                val size = cookie.name.length + cookie.value.length
                if (size > MAX_COOKIE_CHARS) continue
                val key = key(cookie)
                this.cookies.remove(key)
                this.cookies[key] = cookie
            }
            prune()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val result = ArrayList<Cookie>()
            val iterator = cookies.entries.iterator()
            while (iterator.hasNext()) {
                val cookie = iterator.next().value
                if (cookie.expiresAt <= now) {
                    iterator.remove()
                    continue
                }
                if (cookie.matches(url)) result.add(cookie)
            }
            return result
        }
    }

    fun clear() {
        synchronized(lock) { cookies.clear() }
    }

    fun size(): Int = synchronized(lock) { cookies.size }

    private fun prune() {
        while (cookies.size > maxCookies || totalBytes() > maxBytes) {
            val oldest = cookies.entries.firstOrNull() ?: return
            cookies.remove(oldest.key)
        }
    }

    private fun totalBytes(): Long {
        var total = 0L
        for (cookie in cookies.values) {
            total += cookie.name.length + cookie.value.length
        }
        return total
    }

    private fun key(cookie: Cookie): String =
        "${cookie.domain}\u0000${cookie.path}\u0000${cookie.name}"

    companion object {
        const val DEFAULT_MAX_COOKIES = 32
        const val DEFAULT_MAX_BYTES = 64L * 1024
        private const val MAX_COOKIE_CHARS = 4096
    }
}
