package hk.edu.thei.ict.vibecodingpractice4

// Keep your project's package declaration above these imports.

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Small screen selector for this beginner project.
 *
 * A larger app would normally use Navigation Compose.
 */
private enum class Screen {
    LOGIN,
    CONFIGURATION,
    GAME,
    HISTORY
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = BingoPreferences(applicationContext)
        val database = BingoDatabaseHelper(applicationContext)

        /*
         * Constructing SQLiteOpenHelper does not open the database.
         * The database is opened later, on a background thread, when
         * one of its read/write methods is used.
         */
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BingoApp(
                        preferences = preferences,
                        database = database
                    )
                }
            }
        }

        /*
         * The helper is shared by this Activity's UI and its coroutines.
         * Do not close the database after each query or insert.
         *
         * In a larger app, move database ownership into an application-
         * scoped repository and expose it through a ViewModel.
         */
    }
}

/**
 * Creates a standard 5 × 5 Bingo card.
 *
 * Each column has a different number range:
 * B = 1–15
 * I = 16–30
 * N = 31–45
 * G = 46–60
 * O = 61–75
 *
 * The list is row-major:
 * indices 0–4 are row 1, indices 5–9 are row 2, etc.
 */
private fun createBoard(): List<Int> {
    val columns = List(5) { column ->
        val firstNumber = column * 15 + 1

        // Take five distinct random numbers from this column's range.
        (firstNumber..firstNumber + 14)
            .shuffled()
            .take(5)
    }

    return List(25) { index ->
        if (index == 12) {
            // Index 12 is the center of a 25-cell board.
            0
        } else {
            val column = index % 5
            val row = index / 5
            columns[column][row]
        }
    }
}

/**
 * Returns the five CELL INDICES in the first completed line.
 *
 * This differs from returning only true/false: the indices tell us
 * exactly which five board values should be saved in SQLite.
 *
 * If multiple lines are complete, the search order is:
 * rows, then columns, then diagonals.
 */
private fun findWinningLine(
    marked: Set<Int>,
    allowDiagonals: Boolean
): List<Int>? {
    val rows = (0..4).map { row ->
        (0..4).map { column ->
            row * 5 + column
        }
    }

    val columns = (0..4).map { column ->
        (0..4).map { row ->
            row * 5 + column
        }
    }

    val diagonals = if (allowDiagonals) {
        listOf(
            listOf(0, 6, 12, 18, 24),
            listOf(4, 8, 12, 16, 20)
        )
    } else {
        emptyList()
    }

    return (rows + columns + diagonals).firstOrNull { line ->
        line.all { cellIndex -> cellIndex in marked }
    }
}

/**
 * Converts the stored epoch timestamp into readable local date/time.
 *
 * The database keeps the original numeric timestamp, not this formatted
 * text. This allows the display to follow the device locale/time zone.
 */
private fun formatTime(timeMillis: Long): String {
    return DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.MEDIUM
    ).format(Date(timeMillis))
}

@Composable
private fun BingoApp(
    preferences: BingoPreferences,
    database: BingoDatabaseHelper
) {
    /*
     * remember keeps values during recomposition.
     *
     * mutableStateOf causes Compose to redraw affected UI when
     * the value changes.
     *
     * These values are not durable game-state storage: activity
     * recreation or process death still resets the active game.
     */
    var screen by remember { mutableStateOf(Screen.LOGIN) }

    var username by remember {
        mutableStateOf(preferences.username)
    }
    var autoMark by remember {
        mutableStateOf(preferences.autoMark)
    }
    var allowDiagonals by remember {
        mutableStateOf(preferences.allowDiagonals)
    }

    var board by remember { mutableStateOf(createBoard()) }
    var calledNumbers by remember {
        mutableStateOf(emptyList<Int>())
    }

    // Marked values are cell indices, not the numbers printed on the card.
    var marked by remember { mutableStateOf(setOf(12)) }

    // No round exists until the player starts a game.
    var roundNumber by remember { mutableStateOf<Long?>(null) }

    // Tracks whether this round's winning result has been saved.
    var savedRound by remember { mutableStateOf<Long?>(null) }

    // Database-operation flags prevent conflicting user actions.
    var startingRound by remember { mutableStateOf(false) }
    var savingResult by remember { mutableStateOf(false) }

    var saveMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Changing this value lets the player retry a failed result save.
    var saveAttempt by remember { mutableStateOf(0) }

    var history by remember {
        mutableStateOf(emptyList<BingoResult>())
    }
    var loadingHistory by remember { mutableStateOf(false) }

    /*
     * This scope is tied to the composable's lifecycle.
     * Button handlers use it to launch coroutine-based work.
     */
    val scope = rememberCoroutineScope()

    /**
     * Creates a database round before replacing the current board.
     *
     * If the database operation fails, the existing game remains intact.
     */
    fun startNewRound() {
        if (startingRound || savingResult) return

        startingRound = true
        errorMessage = null

        // Capture the player now rather than reading changing UI state later.
        val player = username

        scope.launch {
            try {
                val newRoundNumber = withContext(Dispatchers.IO) {
                    database.createRound(player)
                }

                // After withContext returns, execution resumes on the UI thread.
                board = createBoard()
                calledNumbers = emptyList()
                marked = setOf(12)
                roundNumber = newRoundNumber
                savedRound = null
                saveMessage = ""
                saveAttempt = 0
                screen = Screen.GAME
            } catch (cancelled: CancellationException) {
                // Never swallow coroutine cancellation.
                throw cancelled
            } catch (exception: Exception) {
                errorMessage =
                    "Could not start the round: ${exception.localizedMessage}"
            } finally {
                startingRound = false
            }
        }
    }

    val winningLine = findWinningLine(
        marked = marked,
        allowDiagonals = allowDiagonals
    )

    /*
     * Save a result when a winning line appears.
     *
     * Do not insert directly in the composable body: recomposition
     * can execute that body many times.
     *
     * LaunchedEffect runs coroutine work when its keys change.
     * The database UNIQUE constraint provides a second layer of
     * protection against duplicate results.
     */
    LaunchedEffect(roundNumber, winningLine, saveAttempt) {
        val currentRound = roundNumber
        val line = winningLine

        if (
            currentRound != null &&
            line != null &&
            savedRound != currentRound
        ) {
            savingResult = true
            saveMessage = "Saving Bingo result..."
            errorMessage = null

            // Snapshot all values needed by the background operation.
            val player = username
            val winningNumbers = line.map { index -> board[index] }

            // Record detection time, not the later history-display time.
            val detectedAt = System.currentTimeMillis()

            try {
                withContext(Dispatchers.IO) {
                    database.saveBingo(
                        username = player,
                        roundNumber = currentRound,
                        numbers = winningNumbers,
                        systemTimeMillis = detectedAt
                    )
                }

                savedRound = currentRound
                saveMessage = "Bingo result saved to SQLite."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                saveMessage = "Bingo result was not saved."
                errorMessage =
                    "Database error: ${exception.localizedMessage}"
            } finally {
                savingResult = false
            }
        }
    }

    /**
     * Reload history whenever the user enters the history screen.
     */
    LaunchedEffect(screen, username) {
        if (screen == Screen.HISTORY) {
            loadingHistory = true
            history = emptyList()
            errorMessage = null

            try {
                val player = username

                history = withContext(Dispatchers.IO) {
                    database.getResultsForUser(player)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                errorMessage =
                    "Could not load history: ${exception.localizedMessage}"
            } finally {
                loadingHistory = false
            }
        }
    }

    /*
     * Once a Bingo is detected, freeze this round's play.
     * This keeps the winning line stable while its result is saved.
     */
    val roundFinished =
        winningLine != null ||
                (roundNumber != null && savedRound == roundNumber)

    val databaseBusy = startingRound || savingResult

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Shared error area, visible on whichever screen is active.
        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error
            )
        }

        when (screen) {
            Screen.LOGIN -> {
                Text(
                    text = "Welcome to Bingo",
                    style = MaterialTheme.typography.headlineMedium
                )

                Text("Enter a username to continue.")

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = username.isNotBlank(),
                    onClick = {
                        username = username.trim()

                        // Persist the username for the next launch.
                        preferences.username = username
                        errorMessage = null
                        screen = Screen.CONFIGURATION
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue")
                }

                Text(
                    text = "Demo login only — no account authentication.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Screen.CONFIGURATION -> {
                Text(
                    text = "Configuration",
                    style = MaterialTheme.typography.headlineMedium
                )

                Text("Player: $username")

                SettingSwitch(
                    label = "Automatically mark called numbers",
                    checked = autoMark,
                    onCheckedChange = { enabled ->
                        autoMark = enabled
                        preferences.autoMark = enabled

                        /*
                         * When auto-mark is enabled during an unfinished
                         * game, mark matching numbers already called.
                         */
                        if (enabled && !roundFinished) {
                            marked = marked + board.indices.filter { index ->
                                board[index] in calledNumbers
                            }
                        }
                    }
                )

                SettingSwitch(
                    label = "Allow diagonal Bingo",
                    checked = allowDiagonals,
                    onCheckedChange = { enabled ->
                        allowDiagonals = enabled
                        preferences.allowDiagonals = enabled
                    }
                )

                Text("Settings are saved using SharedPreferences.")

                Button(
                    enabled = !databaseBusy,
                    onClick = {
                        if (roundNumber == null) {
                            startNewRound()
                        } else {
                            screen = Screen.GAME
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            startingRound -> "Starting..."
                            roundNumber == null -> "Start game"
                            else -> "Return to game"
                        }
                    )
                }

                OutlinedButton(
                    enabled = !databaseBusy,
                    onClick = { screen = Screen.HISTORY },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View Bingo history")
                }

                TextButton(
                    enabled = !databaseBusy,
                    onClick = {
                        // Logout clears the active game, not saved history.
                        roundNumber = null
                        savedRound = null
                        board = createBoard()
                        calledNumbers = emptyList()
                        marked = setOf(12)
                        saveMessage = ""
                        errorMessage = null
                        screen = Screen.LOGIN
                    }
                ) {
                    Text("Log out")
                }
            }

            Screen.GAME -> {
                Text(
                    text = "Bingo — $username",
                    style = MaterialTheme.typography.headlineMedium
                )

                Text("Round: ${roundNumber ?: "—"}")

                Text(
                    text = "Last call: ${calledNumbers.lastOrNull() ?: "—"}",
                    style = MaterialTheme.typography.titleLarge
                )

                if (roundFinished) {
                    Text(
                        text = "BINGO! 🎉",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

                if (saveMessage.isNotEmpty()) {
                    Text(saveMessage)
                }

                if (
                    roundFinished &&
                    savedRound != roundNumber &&
                    !savingResult
                ) {
                    OutlinedButton(
                        onClick = { saveAttempt += 1 }
                    ) {
                        Text("Retry saving result")
                    }
                }

                // Column labels above the card.
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("B", "I", "N", "G", "O").forEach { heading ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = heading,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }

                // Draw five rows containing five cells each.
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(5) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(5) { column ->
                                val index = row * 5 + column
                                val number = board[index]
                                val isMarked = index in marked

                                /*
                                 * Uncalled cells cannot be marked.
                                 * The free center remains permanently marked.
                                 */
                                val canTap =
                                    number != 0 &&
                                            number in calledNumbers &&
                                            !roundFinished &&
                                            !databaseBusy

                                Button(
                                    onClick = {
                                        // Build a new Set so Compose sees a change.
                                        marked = if (isMarked) {
                                            marked - index
                                        } else {
                                            marked + index
                                        }
                                    },
                                    enabled = canTap,
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f),
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isMarked) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        contentColor = if (isMarked) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        disabledContainerColor = if (isMarked) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        disabledContentColor = if (isMarked) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                ) {
                                    Text(
                                        text = if (number == 0) {
                                            "FREE"
                                        } else {
                                            number.toString()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    enabled = calledNumbers.size < 75 &&
                            !roundFinished &&
                            !databaseBusy,
                    onClick = {
                        // Select only from numbers not previously called.
                        val remaining = (1..75).filter { number ->
                            number !in calledNumbers
                        }

                        val nextNumber = remaining.random()
                        calledNumbers = calledNumbers + nextNumber

                        if (autoMark) {
                            marked = marked + board.indices.filter { index ->
                                board[index] == nextNumber
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Call next number")
                }

                Text("Numbers called: ${calledNumbers.size}/75")

                if (!autoMark && !roundFinished) {
                    Text("Tap a called number on your card to mark it.")
                }

                OutlinedButton(
                    enabled = !databaseBusy,
                    onClick = { startNewRound() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (startingRound) "Starting..." else "New game")
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = !databaseBusy,
                        onClick = { screen = Screen.CONFIGURATION }
                    ) {
                        Text("Settings")
                    }

                    OutlinedButton(
                        enabled = !databaseBusy,
                        onClick = { screen = Screen.HISTORY }
                    ) {
                        Text("History")
                    }
                }
            }

            Screen.HISTORY -> {
                Text(
                    text = "Bingo history",
                    style = MaterialTheme.typography.headlineMedium
                )

                Text("Player: $username")
                Text("Showing up to 50 recent results.")

                when {
                    loadingHistory -> {
                        CircularProgressIndicator()
                    }

                    history.isEmpty() -> {
                        Text("No saved results to display.")
                    }

                    else -> {
                        history.forEach { result ->
                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Round ${result.roundNumber}",
                                        style = MaterialTheme.typography.titleMedium
                                    )

                                    Text("Username: ${result.username}")

                                    val numbersText =
                                        result.numbers.joinToString(", ") {
                                            if (it == 0) "FREE" else it.toString()
                                        }

                                    Text("Bingo numbers: $numbersText")

                                    Text(
                                        "System time: " +
                                                formatTime(result.systemTimeMillis)
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    enabled = !databaseBusy,
                    onClick = {
                        screen = if (roundNumber == null) {
                            Screen.CONFIGURATION
                        } else {
                            Screen.GAME
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back")
                }
            }
        }
    }
}

/**
 * Reusable settings row.
 *
 * The parent owns the setting value. This composable simply displays
 * that value and reports user changes through onCheckedChange.
 */
@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}