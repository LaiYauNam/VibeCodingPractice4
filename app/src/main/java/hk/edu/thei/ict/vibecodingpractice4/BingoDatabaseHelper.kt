package hk.edu.thei.ict.vibecodingpractice4

// Keep your project's package declaration above these imports.

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A Kotlin representation of one saved database record.
 *
 * SQLite stores the five numbers in separate columns.
 * The application exposes them together as a List<Int>.
 */
data class BingoResult(
    val id: Long,
    val username: String,
    val roundNumber: Long,
    val numbers: List<Int>,
    val systemTimeMillis: Long
)

/**
 * SQLiteOpenHelper manages database creation and schema versions.
 *
 * The database is stored in the application's private internal storage.
 * No external-storage permission is required.
 */
class BingoDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "bingo.db"
        private const val DATABASE_VERSION = 1
    }

    /**
     * Called whenever a database connection is configured.
     *
     * Foreign-key enforcement ensures that a Bingo result references
     * a round that actually exists.
     */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    /**
     * Called once when the database is created for the first time.
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE game_rounds (
                round_number INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                started_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE bingo_results (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                round_number INTEGER NOT NULL UNIQUE,
                number_1 INTEGER NOT NULL,
                number_2 INTEGER NOT NULL,
                number_3 INTEGER NOT NULL,
                number_4 INTEGER NOT NULL,
                number_5 INTEGER NOT NULL,
                system_time INTEGER NOT NULL,
                FOREIGN KEY (round_number)
                    REFERENCES game_rounds(round_number)
            )
            """.trimIndent()
        )

        /*
         * UNIQUE on round_number prevents two winning records from
         * being saved for the same round.
         *
         * INTEGER is also suitable for timestamps and Kotlin Long values.
         */
    }

    /**
     * Called only when DATABASE_VERSION is increased.
     *
     * This is database schema version 1, even though the application
     * itself is Version 2.
     *
     * When changing the schema later, increase DATABASE_VERSION and
     * add explicit migrations here. Do not drop tables if you want
     * to preserve player history.
     */
    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // No migrations are needed for the initial schema.
    }

    /**
     * Creates a round and returns its generated round number.
     *
     * This method performs disk work. Call it on a background thread.
     */
    fun createRound(username: String): Long {
        require(username.isNotBlank()) {
            "Username must not be blank."
        }

        val values = ContentValues().apply {
            put("username", username)
            put("started_at", System.currentTimeMillis())
        }

        // insertOrThrow reports database failures as exceptions.
        return writableDatabase.insertOrThrow(
            "game_rounds",
            null,
            values
        )
    }

    /**
     * Saves the five cells in a winning line.
     *
     * A free center is represented by 0.
     *
     * This method is idempotent for a round: if that round was already
     * saved, the UNIQUE constraint causes the duplicate insert to be
     * ignored rather than creating another result.
     *
     * Call this method on a background thread.
     */
    fun saveBingo(
        username: String,
        roundNumber: Long,
        numbers: List<Int>,
        systemTimeMillis: Long
    ) {
        require(username.isNotBlank())
        require(roundNumber > 0)
        require(numbers.size == 5) {
            "A Bingo result must contain exactly five cells."
        }
        require(numbers.all { it in 0..75 }) {
            "Numbers must be between 0 and 75."
        }

        val values = ContentValues().apply {
            put("username", username)
            put("round_number", roundNumber)

            numbers.forEachIndexed { index, number ->
                put("number_${index + 1}", number)
            }

            put("system_time", systemTimeMillis)
        }

        val db = writableDatabase

        val insertedId = db.insertWithOnConflict(
            "bingo_results",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        /*
         * A return value of -1 can mean the insert was ignored.
         * Check that this round already has a result before treating
         * the operation as successful.
         */
        if (insertedId == -1L) {
            db.query(
                "bingo_results",
                arrayOf("id"),
                "round_number = ?",
                arrayOf(roundNumber.toString()),
                null,
                null,
                null
            ).use { cursor ->
                check(cursor.moveToFirst()) {
                    "The Bingo result could not be saved."
                }
            }
        }
    }

    /**
     * Loads recent results, newest saved result first.
     *
     * selectionArgs binds the username safely. It is not concatenated
     * into SQL, so usernames containing quotes remain valid.
     *
     * Cursor.use automatically closes the Cursor after reading it.
     */
    fun getResultsForUser(
        username: String,
        limit: Int = 50
    ): List<BingoResult> {
        require(limit > 0)

        val results = mutableListOf<BingoResult>()

        readableDatabase.query(
            "bingo_results",
            arrayOf(
                "id",
                "username",
                "round_number",
                "number_1",
                "number_2",
                "number_3",
                "number_4",
                "number_5",
                "system_time"
            ),
            "username = ?",
            arrayOf(username),
            null,
            null,
            "id DESC",
            limit.toString()
        ).use { cursor ->

            // Resolve column positions once, rather than for every row.
            val idColumn = cursor.getColumnIndexOrThrow("id")
            val usernameColumn = cursor.getColumnIndexOrThrow("username")
            val roundColumn = cursor.getColumnIndexOrThrow("round_number")
            val timeColumn = cursor.getColumnIndexOrThrow("system_time")

            val numberColumns = (1..5).map { number ->
                cursor.getColumnIndexOrThrow("number_$number")
            }

            while (cursor.moveToNext()) {
                results.add(
                    BingoResult(
                        id = cursor.getLong(idColumn),
                        username = cursor.getString(usernameColumn),
                        roundNumber = cursor.getLong(roundColumn),
                        numbers = numberColumns.map { column ->
                            cursor.getInt(column)
                        },
                        systemTimeMillis = cursor.getLong(timeColumn)
                    )
                )
            }
        }

        return results
    }
}