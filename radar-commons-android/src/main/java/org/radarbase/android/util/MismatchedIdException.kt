package org.radarbase.android.util

import java.io.IOException

class MismatchedIdException : RuntimeException {
    constructor(message: String?): super(message)
    constructor(message: String?, cause: Throwable?): super(message, cause)
}