package dev.omatube.app.backend

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory cookie store: bounded, never persisted, redirect-cookie aware. */
class BoundedCookieJarTest {

    private val url: HttpUrl = "https://www.youtube.com/watch".toHttpUrl()

    @Test
    fun savesAndLoadsMatchingCookies() {
        val jar = BoundedCookieJar()
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "session=abc; Path=/"))))
        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("session", loaded[0].name)
        assertEquals("abc", loaded[0].value)
    }

    @Test
    fun replacesCookieWithSameIdentity() {
        val jar = BoundedCookieJar()
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "session=old; Path=/"))))
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "session=new; Path=/"))))
        assertEquals(1, jar.size())
        assertEquals("new", jar.loadForRequest(url).single().value)
    }

    @Test
    fun evictsOldestWhenOverLimit() {
        val jar = BoundedCookieJar(maxCookies = 2)
        for (i in 1..3) {
            jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "c$i=$i; Path=/"))))
        }
        assertEquals(2, jar.size())
        val names = jar.loadForRequest(url).map { it.name }.toSet()
        assertFalse(names.contains("c1"))
        assertTrue(names.containsAll(setOf("c2", "c3")))
    }

    @Test
    fun enforcesByteBudget() {
        val jar = BoundedCookieJar(maxCookies = 100, maxBytes = 20)
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "a=${"x".repeat(50)}; Path=/"))))
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "b=bb; Path=/"))))
        assertTrue(jar.size() <= 1)
    }

    @Test
    fun dropsExpiredCookies() {
        val jar = BoundedCookieJar()
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "gone=1; Max-Age=0; Path=/"))))
        assertEquals(0, jar.loadForRequest(url).size)
        assertEquals(0, jar.size())
    }

    @Test
    fun doesNotReturnCookiesForAnotherHost() {
        val jar = BoundedCookieJar()
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "session=abc; Path=/"))))
        val other = "https://example.test/".toHttpUrl()
        assertTrue(jar.loadForRequest(other).isEmpty())
    }

    @Test
    fun skipsOversizedCookies() {
        val jar = BoundedCookieJar()
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "big=${"x".repeat(5000)}; Path=/"))))
        assertEquals(0, jar.size())
    }

    @Test
    fun clearRemovesEverything() {
        val jar = BoundedCookieJar()
        jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, "a=1; Path=/"))))
        jar.clear()
        assertEquals(0, jar.size())
    }
}
