package org.radarbase.android.util.exceptions

class MismatchedUserIdException : RuntimeException {
    constructor(message: String?): super(message)
    constructor(message: String?, cause: Throwable?): super(message, cause)
}
