package dev.bookah.calendar.data

/** One colour the event form offers. [name] is the CSS colour name written to the file (RFC 7986 COLOR), [hex] what the screens draw. */
data class EventColorChoice(val name: String, val hex: String)

/**
 * Per-event colours. The file keeps a CSS colour name in the COLOR property (what Nextcloud's own calendar writes and reads); events without
 * one use their calendar's colour.
 */
object EventColors {
    val palette: List<EventColorChoice> = listOf(
        EventColorChoice("tomato", "#FF6347"), EventColorChoice("salmon", "#FA8072"), EventColorChoice("orange", "#FFA500"),
        EventColorChoice("gold", "#FFD700"), EventColorChoice("mediumseagreen", "#3CB371"), EventColorChoice("seagreen", "#2E8B57"),
        EventColorChoice("turquoise", "#40E0D0"), EventColorChoice("royalblue", "#4169E1"), EventColorChoice("mediumpurple", "#9370DB"),
        EventColorChoice("purple", "#800080"), EventColorChoice("hotpink", "#FF69B4"), EventColorChoice("gray", "#808080"),
    )

    /** Other CSS names that may come from other programs (read only; the form does not offer them). */
    private val extra = mapOf(
        "red" to "#FF0000", "blue" to "#0000FF", "green" to "#008000", "yellow" to "#FFFF00", "black" to "#000000", "white" to "#FFFFFF",
        "pink" to "#FFC0CB", "brown" to "#A52A2A", "cyan" to "#00FFFF", "magenta" to "#FF00FF", "navy" to "#000080", "teal" to "#008080",
        "olive" to "#808000", "maroon" to "#800000", "lime" to "#00FF00", "aqua" to "#00FFFF", "silver" to "#C0C0C0", "grey" to "#808080",
        "crimson" to "#DC143C", "coral" to "#FF7F50", "khaki" to "#F0E68C", "indigo" to "#4B0082", "violet" to "#EE82EE", "orchid" to "#DA70D6",
        "dodgerblue" to "#1E90FF", "deepskyblue" to "#00BFFF", "skyblue" to "#87CEEB", "steelblue" to "#4682B4", "slateblue" to "#6A5ACD",
        "forestgreen" to "#228B22", "limegreen" to "#32CD32", "darkgreen" to "#006400", "olivedrab" to "#6B8E23", "chocolate" to "#D2691E",
        "sienna" to "#A0522D", "firebrick" to "#B22222", "darkorange" to "#FF8C00", "goldenrod" to "#DAA520", "lightcoral" to "#F08080",
        "darkblue" to "#00008B", "midnightblue" to "#191970", "slategray" to "#708090", "darkgray" to "#A9A9A9", "lightgray" to "#D3D3D3",
        "plum" to "#DDA0DD", "lavender" to "#E6E6FA", "peru" to "#CD853F", "tan" to "#D2B48C", "beige" to "#F5F5DC", "mediumblue" to "#0000CD",
    )

    /** "tomato", "#ff6347" or "#f63" as a "#RRGGBB" string, or null for anything else (the calendar's colour is then used). */
    fun resolve(value: String?): String? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.isEmpty()) return null
        palette.firstOrNull { it.name == v }?.let { return it.hex }
        extra[v]?.let { return it }
        Regex("^#([0-9a-f]{6})$").matchEntire(v)?.let { return "#" + it.groupValues[1].uppercase() }
        Regex("^#([0-9a-f])([0-9a-f])([0-9a-f])$").matchEntire(v)?.let { m -> return "#" + m.groupValues.drop(1).joinToString("") { "$it$it" }.uppercase() }
        return null
    }

    /** The palette entry whose colour is [hex], for showing the current choice in the form. */
    fun choiceFor(hex: String?): EventColorChoice? = hex?.let { h -> palette.firstOrNull { it.hex.equals(h, ignoreCase = true) } }
}
