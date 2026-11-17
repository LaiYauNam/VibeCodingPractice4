package hk.edu.thei.ict.vibecodingpractice4

// Keep your project's package declaration above these imports.

import android.content.Context

/**
 * Stores small application settings.
 *
 * SharedPreferences is appropriate here because we only need a few
 * key/value pairs, rather than a collection of structured records.
 *
 * Bingo results are stored separately in SQLite.
 */
class BingoPreferences(context: Context) {

    /**
     * applicationContext avoids retaining an Activity reference.
     *
     * MODE_PRIVATE means this preferences file is private to this app.
     */
    private val preferences = context.applicationContext
        .getSharedPreferences(
            "bingo_preferences",
            Context.MODE_PRIVATE
        )

    /**
     * Returns the previously saved username.
     * If nothing has been saved, return an empty string.
     */
    var username: String
        get() = preferences.getString("username", "") ?: ""
        set(value) {
            // apply() saves asynchronously.
            preferences.edit()
                .putString("username", value)
                .apply()
        }

    /**
     * Determines whether called numbers are marked automatically.
     *
     * true is the default for a new installation.
     */
    var autoMark: Boolean
        get() = preferences.getBoolean("auto_mark", true)
        set(value) {
            preferences.edit()
                .putBoolean("auto_mark", value)
                .apply()
        }

    /**
     * Determines whether diagonal lines count as Bingo.
     *
     * Rows and columns always count.
     */
    var allowDiagonals: Boolean
        get() = preferences.getBoolean("allow_diagonals", true)
        set(value) {
            preferences.edit()
                .putBoolean("allow_diagonals", value)
                .apply()
        }
}