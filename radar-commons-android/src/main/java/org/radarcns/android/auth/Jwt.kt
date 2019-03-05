package org.radarcns.android.auth

import android.util.Base64
import android.util.Base64.NO_PADDING
import android.util.Base64.NO_WRAP
import org.json.JSONException
import org.json.JSONObject

class Jwt(val originalString: String, val header: JSONObject, val body: JSONObject) {
    companion object {
        private val jwtSeparatorCharacter = "\\.".toRegex()

        @Throws(JSONException::class)
        fun parse(token: String): Jwt {
            val parts = token.split(jwtSeparatorCharacter)
                    .dropLastWhile { it.isEmpty() }
            if (parts.size != 3) {
                throw IllegalArgumentException(
                        "Argument is not a valid JSON web token. Need 3 parts but got " + parts.size)
            }
            val headerString = String(Base64.decode(parts[0], NO_PADDING or NO_WRAP))
            val header = JSONObject(headerString)

            val bodyString = String(Base64.decode(parts[1], NO_PADDING or NO_WRAP))
            val body = JSONObject(bodyString)

            return Jwt(token, header, body)
        }
    }
}
