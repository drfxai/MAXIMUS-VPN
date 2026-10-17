package com.example.ui.home

import java.util.Locale

/** Where a country sits on the globe: a point near its centre (or its main city for small countries). */
object EarthPlaces {
    private val POINTS: Map<String, Pair<Float, Float>> = mapOf(
        "IR" to (32.4f to 53.7f), "US" to (39.5f to -98.4f), "CA" to (56.1f to -106.3f), "MX" to (23.6f to -102.6f),
        "BR" to (-10.3f to -53.1f), "AR" to (-38.4f to -63.6f), "CL" to (-33.4f to -70.6f), "CO" to (4.6f to -74.1f),
        "GB" to (52.4f to -1.5f), "IE" to (53.3f to -7.7f), "FR" to (46.6f to 2.4f), "DE" to (51.2f to 10.4f),
        "NL" to (52.2f to 5.3f), "BE" to (50.6f to 4.6f), "LU" to (49.6f to 6.1f), "CH" to (46.8f to 8.2f),
        "AT" to (47.6f to 14.1f), "IT" to (42.8f to 12.5f), "ES" to (40.2f to -3.6f), "PT" to (39.6f to -8.0f),
        "PL" to (52.1f to 19.4f), "CZ" to (49.8f to 15.5f), "SK" to (48.7f to 19.7f), "HU" to (47.2f to 19.5f),
        "RO" to (45.9f to 24.9f), "BG" to (42.7f to 25.5f), "GR" to (39.1f to 22.0f), "RS" to (44.0f to 20.9f),
        "HR" to (45.1f to 15.2f), "SI" to (46.1f to 14.8f), "UA" to (49.0f to 31.4f), "MD" to (47.0f to 28.4f),
        "LT" to (55.2f to 23.9f), "LV" to (56.9f to 24.6f), "EE" to (58.6f to 25.0f), "FI" to (64.0f to 26.0f),
        "SE" to (62.2f to 17.6f), "NO" to (61.0f to 8.5f), "DK" to (56.0f to 10.0f), "IS" to (64.9f to -18.6f),
        "RU" to (61.5f to 90.0f), "TR" to (39.0f to 35.2f), "CY" to (35.1f to 33.4f), "IL" to (31.4f to 35.0f),
        "AE" to (24.0f to 54.0f), "SA" to (24.0f to 45.0f), "QA" to (25.3f to 51.2f), "BH" to (26.0f to 50.6f),
        "KW" to (29.3f to 47.6f), "OM" to (21.5f to 55.9f), "IQ" to (33.2f to 43.7f), "AM" to (40.1f to 45.0f),
        "AZ" to (40.1f to 47.6f), "GE" to (42.3f to 43.4f), "AF" to (33.9f to 67.7f), "PK" to (30.4f to 69.3f),
        "IN" to (21.0f to 78.9f), "KZ" to (48.0f to 67.0f), "UZ" to (41.4f to 64.6f), "TM" to (38.9f to 59.6f),
        "CN" to (35.9f to 104.2f), "HK" to (22.3f to 114.2f), "TW" to (23.7f to 121.0f), "JP" to (36.2f to 138.3f),
        "KR" to (36.5f to 127.9f), "SG" to (1.35f to 103.8f), "MY" to (4.2f to 101.9f), "TH" to (15.9f to 100.9f),
        "VN" to (14.1f to 108.3f), "ID" to (-2.5f to 118.0f), "PH" to (12.9f to 121.8f), "AU" to (-25.3f to 133.8f),
        "NZ" to (-41.0f to 174.0f), "ZA" to (-30.6f to 22.9f), "EG" to (26.8f to 30.8f), "MA" to (31.8f to -7.1f),
        "NG" to (9.1f to 8.7f), "KE" to (0.0f to 37.9f), "TZ" to (-6.4f to 34.9f), "ET" to (9.1f to 40.5f)
    )

    /** The point for an ISO country code, or null when the table does not have it. */
    fun of(code: String?): Pair<Float, Float>? = code?.uppercase(Locale.US)?.let(POINTS::get)
}
