package dev.omatube.app.data

// Test-only helper: JUnit's assertThrows takes a non-suspending lambda, so
// suspend repository calls need this wrapper to assert failures.
internal suspend fun <T : Throwable> expectFailure(type: Class<T>, block: suspend () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (type.isInstance(error)) return type.cast(error)
        throw error
    }
    throw AssertionError("Expected ${type.name} to be thrown.")
}
