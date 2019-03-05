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

package org.radarcns.android.auth

import android.app.Activity
import android.content.Intent
import com.google.zxing.client.android.Intents.Scan.MODE
import com.google.zxing.client.android.Intents.Scan.QR_CODE_MODE
import com.google.zxing.integration.android.IntentIntegrator

/**
 * QR code scanner.
 * @param callback result contents callback
 * @param activity to call back to when scanning has finished.
 */
class QrCodeScanner(private val activity: Activity, private val callback: (String?) -> Unit) {
    fun start() {
        val qrIntegrator = IntentIntegrator(activity)
        qrIntegrator.addExtra(MODE, QR_CODE_MODE)
        qrIntegrator.initiateScan()
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        callback(IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.contents)
    }
}
