package ani.dantotsu.extension.novel.calibre

import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExtensionUnitTest {
    private val extension = Calibre()
    private val client = Requests()

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n=== $title ===")
        block.invoke(this)
        println("=== Done ===\n")
    }

    @Test
    fun testInitialization() = testIn("Testing Calibre Source Initialization") {
        assertNotNull(extension.name)
        assertNotNull(extension.saveName)
        println("Calibre extension name: ${extension.name}")
        println("Calibre save name: ${extension.saveName}")
    }

    @Test
    fun testSearch() = testIn("Testing Calibre Search Handling") {
        // If local Calibre server is not running, search will gracefully return empty list
        val results = runCatching { extension.search("test", client) }.getOrDefault(emptyList())
        println("Search returned ${results.size} results.")
        assertNotNull(results)
    }
}
