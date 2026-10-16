package ani.dantotsu.parsers

import ani.dantotsu.FileUrl
import java.io.Serializable

typealias FileUrl = ani.dantotsu.FileUrl

data class ShowResponse(
    val name: String,
    val link: String,
    val coverUrl: FileUrl,
    val otherNames: List<String> = listOf(),
    val total: Int? = null,
    val extra: MutableMap<String, String>? = null
) : Serializable {
    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null,
        extra: MutableMap<String, String>? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total, extra)

    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total)

    constructor(name: String, link: String, coverUrl: String, otherNames: List<String> = listOf())
            : this(name, link, FileUrl(coverUrl), otherNames)

    constructor(name: String, link: String, coverUrl: String)
            : this(name, link, FileUrl(coverUrl))
}
