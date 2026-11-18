package dev.bookah.calendar

import java.net.URLEncoder

/** BookahTasks' package: the only app this one opens for tasks (declared in the manifest's queries so Android lets us see it). */
const val TASKS_PACKAGE = "dev.bookah.tasks"

/**
 * SHA-256 of BookahTasks' RELEASE signing certificate (the BookahTasks repo's `android\release-signing-cert.sha256`; a public value, it is in
 * every APK). The list's address, which holds the Nextcloud user name, goes in the link only when the installed BookahTasks is signed with it.
 * BookahCalendar has a key of its own, so this is not this app's certificate.
 */
const val BOOKAH_TASKS_CERT_SHA256 = "634ad38b96fb32bba4de6ee6f5b43181b379371f650b68a8140c9bd76e87c646"

/** A UID longer than this is not put in a link (no real task has one). */
const val MAX_LINK_UID = 1024

/** A longer link is not sent: BookahTasks is just started instead. */
const val MAX_LINK_LENGTH = 4096

/**
 * The link that opens one task in BookahTasks: `bookahtasks://task?uid=<UID>&list=<list href>`, both values percent-encoded as UTF-8; without
 * [listHref] (null) only the UID is given. A space is written %20, never "+": Android's getQueryParameter reads "+" as a space but Uri.decode
 * does not, so "+" would be read two ways. Only "A-Z a-z 0-9 - . _" stay as they are. Null when the UID is longer than [MAX_LINK_UID] or the
 * link longer than [MAX_LINK_LENGTH] (the caller then just starts BookahTasks). Pure Kotlin (no android.net.Uri), so it is tested on the JVM.
 */
fun bookahTasksLink(uid: String, listHref: String?): String? {
    if (uid.length > MAX_LINK_UID) return null
    val link = "bookahtasks://task?uid=${encodeQueryValue(uid)}" + (listHref?.let { "&list=${encodeQueryValue(it)}" } ?: "")
    return link.takeIf { it.length <= MAX_LINK_LENGTH }
}

internal fun encodeQueryValue(value: String): String =
    // the charset-name form: URLEncoder.encode(String, Charset) needs Android 13 and the app runs from Android 12.
    // URLEncoder keeps "*" and turns a space into "+"; both are made unambiguous here
    URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A")

/** "634a..." (64 hex digits) as the 32 bytes PackageManager.hasSigningCertificate wants. */
fun fingerprintBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
