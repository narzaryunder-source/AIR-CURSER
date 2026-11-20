package com.aircursor.core

/**
 * Checks the 6-digit pairing code of a phone that wants to be the keyboard and touchpad.
 *
 * Anyone on the same Wi-Fi can try codes, so: one check at a time (parallel connections do not
 * get parallel guesses), a pause after every wrong code (at most about one guess per second), and
 * a new code after [maxWrong] wrong ones in a row.
 */
class PairingGate(
    private val currentCode: () -> String,
    private val renewCode: () -> Unit,
    private val maxWrong: Int = 5,
    private val wrongPauseMs: Long = 1000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var wrong = 0

    /** True when [given] is the current code. A wrong code costs a pause before the answer. */
    @Synchronized
    fun check(given: String?): Boolean {
        if (given != null && given == currentCode()) {
            wrong = 0
            return true
        }
        if (++wrong >= maxWrong) {
            wrong = 0
            renewCode()
        }
        if (wrongPauseMs > 0) sleep(wrongPauseMs)
        return false
    }

    /** Wrong codes in a row so far (for tests). */
    @get:Synchronized
    val wrongInARow get() = wrong
}
