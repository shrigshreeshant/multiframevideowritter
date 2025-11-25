package com.fishtechy.multiframevideowritter.videoconfig


data class VideoConfig(
    val totalFrames: Int = 0,
    val neighboringWindowLimit: Int = 0,
    val frameSkippingLimit: Int = 0
) {

    companion object {

        // Safe creator from Map<String, Any?>
        fun fromJson(json: Map<String, Any?>?): VideoConfig {
            if (json == null) return VideoConfig()

            fun getInt(key: String): Int {
                return when (val value = json[key]) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull() ?: 0
                    else -> 0
                }
            }

            return VideoConfig(
                totalFrames = getInt("totalFrames"),
                neighboringWindowLimit = getInt("neighboringWindowLimit"),
                frameSkippingLimit = getInt("frameSkippingLimit")
            )
        }
    }

    // Safe toJson() – never throws
    fun toJson(): Map<String, Any> {
        return mapOf(
            "totalFrames" to totalFrames,
            "neighbouringWindowLimit" to neighbouringWindowLimit,
            "frameSkipingLimit" to frameSkippingLimit
        )
    }
}
