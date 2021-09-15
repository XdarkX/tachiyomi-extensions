package eu.kanade.tachiyomi.extension.en.mangamad

import android.annotation.SuppressLint
import android.net.Uri
import eu.kanade.tachiyomi.annotations.Nsfw
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Nsfw
class MangaMad : ParsedHttpSource() {

    override val lang = "en"

    override val client = network.cloudflareClient

    override val supportsLatest = true
    override val name = "MangaMad"
    override val baseUrl = "https://mangamad.com"

    private val nextPageSelector = ".paginator:not(.order) > a:last-child[href]"
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
    private val dateFormatTimeOnly = SimpleDateFormat("HH:mm a", Locale.ENGLISH)

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/popular?page=$page")

    override fun popularMangaSelector() = ".book-item"

    override fun popularMangaFromElement(element: Element) = mangaFromElement(element)

    override fun popularMangaNextPageSelector() = nextPageSelector

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/latest" + if (page > 1) "?page=$page" else "")

    override fun latestUpdatesSelector() = ".book-item"

    override fun latestUpdatesFromElement(element: Element) = mangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = nextPageSelector

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val uri = Uri.parse("$baseUrl/search").buildUpon()
        if (query.isNotEmpty()) {
            uri.appendQueryParameter("q", query)
        }
        filters.forEach {
            if (it is UriFilter)
                it.addToUri(uri)
        }
        if (page != 1) {
            uri.appendQueryParameter("page", page.toString())
        }
        return GET(uri.toString())
    }

    override fun searchMangaSelector(): String {
        // search results are contained in the second div tag
        return "div.section.box:eq(2) .book-item"
    }

    override fun searchMangaFromElement(element: Element) = mangaFromElement(element)

    override fun searchMangaNextPageSelector() = nextPageSelector

    private fun mangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.select("div.title h3 a").attr("href"))
            title = element.selectFirst("div.title").text()
            thumbnail_url = element.selectFirst("div.thumb a img").attr("abs:data-src")
        }
    }

    @SuppressLint("DefaultLocale")
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        document.select("div.img-cover img").first().let { coverElement ->
            title = coverElement.attr("alt")
            thumbnail_url = coverElement.attr("abs:data-src")
        }

        document.select("div.detail div.meta.box p").forEach {
            when (it.text().trim().toLowerCase().substringBefore(" :")) {
                "authors" -> {
                    author = it.getElementsByTag("a")
                        .joinToString { it.text().substringBefore(",").trim() }
                }
                "genres" -> {
                    genre = it.getElementsByTag("a")
                        .joinToString { it.text().substringBefore(",").trim() }
                }
                "status" -> {
                    status = when (it.getElementsByTag("td").text().trim().toLowerCase()) {
                        "ongoing" -> SManga.ONGOING
                        "completed" -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
            }
        }

        description = document.selectFirst("div.section-body p.content").text().trim()

        // add alternative name to manga description
        val altName = "Alternative Name: "
        document.select("div.detail div.name.box h2").firstOrNull()?.ownText()?.let {
            val names = it.split(";").map { it.trim() }
            if (names.isNotEmpty()) {
                description = when {
                    description.isNullOrBlank() -> altName + names.joinToString()
                    else -> description + "\n\n$altName" + names.joinToString()
                }
            }
        }
    }

    // force network to make sure chapter prefs take effect
    override fun chapterListRequest(manga: SManga): Request {
        return GET(
            baseUrl + "/api/manga" + manga.url + "/chapters?source=detail",
            headers,
            CacheControl.FORCE_NETWORK
        )
    }

    override fun chapterListSelector() = ".chapter-list li"

    override fun chapterListParse(response: Response): List<SChapter> {
        return response.asJsoup()
            .select(chapterListSelector())
            .map(::chapterFromElement)
            .sortedBy { it.chapter_number }
    }

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        name = element.selectFirst(".chapter-title").text()
        setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        chapter_number = Regex("""vol.(\d+).*?chapter.*?(\d+\.?\d?)""")
            .find(name.toLowerCase())
            ?.groupValues
            ?.drop(1)
            ?.let {
                (it.getOrNull(0)?.toFloatOrNull()?.times(10000) ?: 0f).plus(
                    it.getOrNull(1)?.toFloatOrNull() ?: 0f
                )
            } ?: Regex("""chapter.*?(\d+\.?\d?)""").find(name.toLowerCase())
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?: Regex("""chapter.*?(\d+-?\d?)""").find(name.toLowerCase())
                ?.groupValues
                ?.getOrNull(1)
                ?.toFloatOrNull() ?: 0f
        date_upload = parseDate(element.selectFirst(".chapter-update").text().trim())
    }

    @SuppressLint("DefaultLocale")
    private fun parseDate(date: String): Long {
        val lcDate = date.toLowerCase()
        if (lcDate.endsWith("ago")) return parseRelativeDate(lcDate)

        // Handle 'yesterday' and 'today'
        var relativeDate: Calendar? = null
        if (lcDate.startsWith("yesterday")) {
            relativeDate = Calendar.getInstance()
            relativeDate.add(Calendar.DAY_OF_MONTH, -1) // yesterday
        } else if (lcDate.startsWith("today")) {
            relativeDate = Calendar.getInstance()
        }

        relativeDate?.let {
            // Since the date is not specified, it defaults to 1970!
            val time = dateFormatTimeOnly.parse(lcDate.substringAfter(' ')) ?: return 0
            val cal = Calendar.getInstance()
            cal.time = time

            // Copy time to relative date
            it.set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY))
            it.set(Calendar.MINUTE, cal.get(Calendar.MINUTE))
            return it.timeInMillis
        }

        return dateFormat.parse(lcDate)?.time ?: 0
    }

    /**
     * Parses dates in this form:
     * `11 days ago`
     */
    private fun parseRelativeDate(date: String): Long {
        val trimmedDate = date.split(" ")

        if (trimmedDate[2] != "ago") return 0

        val number = when (trimmedDate[0]) {
            "a" -> 1
            else -> trimmedDate[0].toIntOrNull() ?: return 0
        }
        val unit = trimmedDate[1].removeSuffix("s") // Remove 's' suffix

        val now = Calendar.getInstance()

        // Map English unit to Java unit
        val javaUnit = when (unit) {
            "year" -> Calendar.YEAR
            "month" -> Calendar.MONTH
            "week" -> Calendar.WEEK_OF_MONTH
            "day" -> Calendar.DAY_OF_MONTH
            "hour" -> Calendar.HOUR
            "minute" -> Calendar.MINUTE
            "second" -> Calendar.SECOND
            else -> return 0
        }

        now.add(javaUnit, -number)

        return now.timeInMillis
    }

    override fun pageListParse(response: Response): List<Page> {
        return response.asJsoup()
            .select("div.chapter-image img")
            .mapIndexed { index, element ->
                Page(index, imageUrl = element.absUrl("data-src"))
            }
    }

    override fun pageListParse(document: Document): List<Page> =
        throw UnsupportedOperationException("Not used")

    // Unused, we can get image urls directly from the chapter page
    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Not used")

    override fun getFilterList() = FilterList(
        SortFilter(),
        GenreFilterBig(),
        StatusFilter(),
    )

    private class SearchTypeFilter(name: String, val uriParam: String) :
        Filter.Text(name), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            uri.appendQueryParameter(uriParam, state)
        }
    }

    private data class GenreFilter(val uriParam: String, val displayName: String) {
        fun toPair(): Pair<String, String> {
            return Pair(uriParam, displayName)
        }
    }

    private class GenreFilterBig : UriSelectFilter(
        displayName = "Genre",
        uriParam = "genre",
        items = arrayOf(
            // TODO: 14/09/2021 update values
            GenreFilter("all", "All"),
            GenreFilter("action", "Action"),
            GenreFilter("adaptation", "Adaptation"),
            GenreFilter("adult", "Adult"),
            GenreFilter("adventure", "Adventure"),
            GenreFilter("aliens", "Aliens"),
            GenreFilter("animals", "Animals"),
            GenreFilter("anthology", "Anthology"),
            GenreFilter("award-winning", "Award winning"),
            GenreFilter("comedy", "Comedy"),
            GenreFilter("cooking", "Cooking"),
            GenreFilter("crime", "Crime"),
            GenreFilter("crossdressing", "Crossdressing"),
            GenreFilter("delinquents", "Delinquents"),
            GenreFilter("demons", "Demons"),
            GenreFilter("doujinshi", "Doujinshi"),
            GenreFilter("drama", "Drama"),
            GenreFilter("ecchi", "Ecchi"),
            GenreFilter("fan-colored", "Fan colored"),
            GenreFilter("fantasy", "Fantasy"),
            GenreFilter("food", "Food"),
            GenreFilter("full-color", "Full color"),
            GenreFilter("game", "Game"),
            GenreFilter("gender-bender", "Gender bender"),
            GenreFilter("genderswap", "Genderswap"),
            GenreFilter("ghosts", "Ghosts"),
            GenreFilter("gore", "Gore"),
            GenreFilter("gossip", "Gossip"),
            GenreFilter("gyaru", "Gyaru"),
            GenreFilter("harem", "Harem"),
            GenreFilter("historical", "Historical"),
            GenreFilter("horror", "Horror"),
            GenreFilter("incest", "Incest"),
            GenreFilter("isekai", "Isekai"),
            GenreFilter("josei", "Josei"),
            GenreFilter("kids", "Kids"),
            GenreFilter("loli", "Loli"),
            GenreFilter("lolicon", "Lolicon"),
            GenreFilter("long-strip", "Long strip"),
            GenreFilter("mafia", "Mafia"),
            GenreFilter("magic", "Magic"),
            GenreFilter("magical-girls", "Magical girls"),
            GenreFilter("manhwa", "Manhwa"),
            GenreFilter("martial-arts", "Martial arts"),
            GenreFilter("mature", "Mature"),
            GenreFilter("mecha", "Mecha"),
            GenreFilter("medical", "Medical"),
            GenreFilter("military", "Military"),
            GenreFilter("monster-girls", "Monster girls"),
            GenreFilter("monsters", "Monsters"),
            GenreFilter("music", "Music"),
            GenreFilter("mystery", "Mystery"),
            GenreFilter("ninja", "Ninja"),
            GenreFilter("office-workers", "Office workers"),
            GenreFilter("official-colored", "Official colored"),
            GenreFilter("one-shot", "One shot"),
            GenreFilter("parody", "Parody"),
            GenreFilter("philosophical", "Philosophical"),
            GenreFilter("police", "Police"),
            GenreFilter("post-apocalyptic", "Post apocalyptic"),
            GenreFilter("psychological", "Psychological"),
            GenreFilter("reincarnation", "Reincarnation"),
            GenreFilter("reverse-harem", "Reverse harem"),
            GenreFilter("romance", "Romance"),
            GenreFilter("samurai", "Samurai"),
            GenreFilter("school-life", "School life"),
            GenreFilter("sci-fi", "Sci fi"),
            GenreFilter("seinen", "Seinen"),
            GenreFilter("shota", "Shota"),
            GenreFilter("shotacon", "Shotacon"),
            GenreFilter("shoujo", "Shoujo"),
            GenreFilter("shoujo-ai", "Shoujo ai"),
            GenreFilter("shounen", "Shounen"),
            GenreFilter("shounen-ai", "Shounen ai"),
            GenreFilter("slice-of-life", "Slice of life"),
            GenreFilter("smut", "Smut"),
            GenreFilter("space", "Space"),
            GenreFilter("sports", "Sports"),
            GenreFilter("super-power", "Super power"),
            GenreFilter("superhero", "Superhero"),
            GenreFilter("supernatural", "Supernatural"),
            GenreFilter("survival", "Survival"),
            GenreFilter("suspense", "Suspense"),
            GenreFilter("thriller", "Thriller"),
            GenreFilter("time-travel", "Time travel"),
            GenreFilter("toomics", "Toomics"),
            GenreFilter("traditional-games", "Traditional games"),
            GenreFilter("tragedy", "Tragedy"),
            GenreFilter("user-created", "User created"),
            GenreFilter("vampire", "Vampire"),
            GenreFilter("vampires", "Vampires"),
            GenreFilter("video-games", "Video games"),
            GenreFilter("virtual-reality", "Virtual reality"),
            GenreFilter("web-comic", "Web comic"),
            GenreFilter("webtoon", "Webtoon"),
            GenreFilter("wuxia", "Wuxia"),
            GenreFilter("yaoi", "Yaoi"),
            GenreFilter("yuri", "Yuri"),
            GenreFilter("zombies", "Zombies")
        ).map { it.toPair() }.toTypedArray(),
        firstIsUnspecified = true,
        defaultValue = 0
    )

    private class StatusFilter : UriSelectFilter(
        "Status",
        "status",
        arrayOf(
            Pair("all", "All"),
            Pair("ongoing", "Ongoing"),
            Pair("completed", "Completed")
        ),
        firstIsUnspecified = true,
        defaultValue = 0
    )

    private class SortFilter : UriSelectFilter(
        "Sort",
        "sort",
        arrayOf(
            Pair("views", "Most Views"),
            Pair("updated_at", "Recently updated"),
            Pair("created_at", "Recently added"),
            Pair("name", "A-Z"),
            Pair("rating", "Rating"),
            Pair("votes", "Votes")
        ),
        firstIsUnspecified = true,
        defaultValue = 0
    )

    /**
     * Class that creates a select filter. Each entry in the dropdown has a name and a display name.
     * If an entry is selected it is appended as a query parameter onto the end of the URI.
     * If `firstIsUnspecified` is set to true, if the first entry is selected, nothing will be appended on the the URI.
     */
    private open class UriSelectFilter(
        displayName: String,
        val uriParam: String,
        val items: Array<Pair<String, String>>,
        val firstIsUnspecified: Boolean = true,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, items.map { it.second }.toTypedArray(), defaultValue),
        UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            if (state != 0 || !firstIsUnspecified) {
                uri.appendQueryParameter(uriParam, items[state].first)
            }
        }
    }

    /**
     * Represents a filter that is able to modify a URI.
     */
    private interface UriFilter {
        fun addToUri(uri: Uri.Builder)
    }
}
