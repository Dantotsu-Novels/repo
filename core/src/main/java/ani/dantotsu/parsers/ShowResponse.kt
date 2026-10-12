package ani.dantotsu.parsers

import java.io.Serializable

data class ShowResponse(
    val name: String,
    val link: String,
    val coverUrl: FileUrl,
    val otherNames: List<String> = listOf(),
    val total: Int? = null,
    val extra: Map<String, String>? = null
) : Serializable {
    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null,
        extra: Map<String, String>? = null
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

data class FileUrl(
    val url: String,
    val headers: Map<String, String> = mapOf()
) : Serializable {
    companion object {
        operator fun get(url: String?, headers: Map<String, String> = mapOf()): FileUrl? {
            return FileUrl(url ?: return null, headers)
        }
    }
}
