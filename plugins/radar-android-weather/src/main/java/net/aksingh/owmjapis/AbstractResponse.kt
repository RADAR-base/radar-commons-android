/*
 * Copyright (c) 2013-2015 Ashutosh Kumar Singh <me@aksingh.net>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.aksingh.owmjapis

import org.json.JSONObject
import java.io.Serializable

/**
 *
 *
 * Provides default behaviours and implementations for the response from OWM.org
 *
 *
 * @author Ashutosh Kumar Singh
 * @version 2014/12/28
 * @since 2.5.0.3
 */
abstract class AbstractResponse(jsonObj: JSONObject?) : Serializable {
    /**
     * @return Response code if available, otherwise `Integer.MIN_VALUE`.
     */
    /*
         Instance variables
          */
    val responseCode: Int = jsonObj?.optInt(JSON_RESPONSE_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE

    /**
     * @return Raw response if available, otherwise `null`.
     */
    val rawResponse: String? = jsonObj?.toString()

    /**
     * @return `true` if response is valid (downloaded and parsed correctly), otherwise `false`.
     */
    val isValid: Boolean
        get() = responseCode == 200

    /**
     * @return `true` if response code is available, otherwise `false`.
     */
    val hasResponseCode: Boolean
        get() = responseCode != Int.MIN_VALUE

    /**
     * @return `true` if raw response is available, otherwise `false`.
     */
    val hasRawResponse: Boolean
        get() = rawResponse != null

    companion object {
        private const val JSON_RESPONSE_CODE = "cod"

        inline fun <T> JSONObject?.optObjectOrNull(
            field: String,
            mapToObject: (JSONObject) -> T,
        ): T? {
            return mapToObject(this?.optJSONObject(field) ?: return null)
        }

        fun <T> JSONObject?.optObjectList(field: String, mapObj: (JSONObject) -> T): List<T> {
            val array = this?.optJSONArray(field) ?: return emptyList()

            return (0 until array.length())
                .asSequence()
                .mapNotNull { array.optJSONObject(it) }
                .map(mapObj)
                .toList()
        }
    }
}
