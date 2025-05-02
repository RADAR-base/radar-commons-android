package org.radarbase.android.util.exceptions

class MismatchedProjectIdException : RuntimeException {
    constructor(message: String?): super(message)
    constructor(message: String?, cause: Throwable?): super(message, cause)
}