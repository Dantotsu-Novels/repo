package ani.dantotsu.extension.novel.annaarchive

import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionUnitTest {
    private val extension = AnnaArchive()
    private val client = Requests()

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n=== $title ===")
        block.invoke(this)
        println("=== Done ===\n")
    }

    @Test
    fun testSearch() = testIn("Testing Search on Anna's Archive") {
        val query = "Lord of the Mysteries"
        println("Searching for: $query")
        val results = extension.search(query, client)
        println("Found ${results.size} results:")
        results.take(5).forEach {
            println(" - [${it.name}] -> ${it.link}")
        }
        assertTrue("Search results should not be empty", results.isNotEmpty())
    }

    @Test
    fun testLoadBook() = testIn("Testing Load Book Details & Download Links") {
        val query = "Overlord"
        val results = extension.search(query, client)
        assertTrue("Expected search results for $query", results.isNotEmpty())

        val first = results.first()
        println("Loading book details for: ${first.name} (${first.link})")
        val book = extension.loadBook(first.link, first.extra, client)
        assertNotNull("Book object should not be null", book)
        println("Book title: ${book.name}")
        println("Cover URL: ${book.img.url}")
        println("Available download links count: ${book.links.size}")
        book.links.forEachIndexed { i, link ->
            println(" Link #$i: ${link.url}")
        }
        assertTrue("Book should have at least one download link", book.links.isNotEmpty())
    }
}
