/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.android.auth

import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.client.android.Intents.Scan.MODE
import com.google.zxing.client.android.Intents.Scan.QR_CODE_MODE
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * QR code scanner.
 * @param callback result contents callback. Gets a String result if a QR code was scanned,
 *                 `null` otherwise.
 * @param activity to call back to when scanning has finished.
 */
class QrCodeScanner(private val activity: AppCompatActivity, private val callback: (String?) -> Unit) {
    private val launcher = activity.registerForActivityResult(ScanContract()) {
        callback(it.contents)
    }

    /** Start scanning for a QR code. */
    fun start() {
        launcher.launch(ScanOptions().apply {
            addExtra(MODE, QR_CODE_MODE)
        })
    }
}
