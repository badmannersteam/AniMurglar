package com.badmanners.animurglar.media

data class RawSeason(val number: Int?, val folder: String? = null) : Comparable<RawSeason> {
    val label: String
        get() = when (number) {
            null -> folder ?: "Без номера сезона"
            0 -> "Спецвыпуски"
            else -> "Сезон $number"
        }

    override fun compareTo(other: RawSeason) = compareValues(number, other.number)
        .let { result ->
            when (result) {
                0 -> compareValues(folder, other.folder)
                else -> result
            }
        }
}
