package sh.hnet.calculator.engine.number

/** What went wrong in a calculation, as a kind the UI names in its own words. */
enum class CalcError {
    DIVISION_BY_ZERO,
    /** Outside the function's domain: √ of a negative, ln 0, tan 90°, 0^0, a factorial of a fraction. */
    DOMAIN,
    /** Beyond the range of 10^±10000. */
    OVERFLOW,
    /** An expression that can't be read (5 × ÷ 3 pasted, a parenthesis closed that was never opened). */
    SYNTAX,
}

class CalcException(val error: CalcError) : RuntimeException(error.name)

/**
 * Cooperative cancellation: long computations (many digits, big factorials) check the thread's
 * interrupt flag, so a calculation started for a key the user has already typed past stops. The
 * app runs them in `runInterruptible`, which turns the interrupt into a coroutine cancellation.
 */
object Cancel {
    fun check() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }
}
