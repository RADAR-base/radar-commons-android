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

package org.radarbase.passive.phone.usage

import android.Manifest.permission.PACKAGE_USAGE_STATS
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process.myUid
import android.provider.Settings
import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceProvider
import org.radarbase.android.util.PermissionRequester
import org.radarbase.android.util.StartActivityForPermission
import org.radarbase.android.util.singleGrantChecker

class PhoneUsageProvider(radarService: RadarService) : SourceProvider<BaseSourceState>(radarService) {

    override val description: String
        get() = radarService.getString(R.string.phone_usage_description)

    override val serviceClass: Class<PhoneUsageService> = PhoneUsageService::class.java

    override val pluginNames = listOf(
            "phone_usage",
            "usage",
            ".phone.PhoneUsageProvider",
            "org.radarbase.passive.phone.telephony.PhoneUsageProvider",
            "org.radarcns.phone.PhoneUsageProvider")

    override val displayName: String
        get() = radarService.getString(R.string.phoneUsageServiceDisplayName)

    override val permissionsNeeded: List<String> = listOf(PACKAGE_USAGE_STATS)

    override val sourceProducer: String = "ANDROID"
    override val sourceModel: String = "PHONE"

    override val version: String = BuildConfig.VERSION_NAME

    override val isDisplayable: Boolean = false

    override val requestPermissionResultContract: List<PermissionRequester> = listOf(
        PermissionRequester(
            permissions = setOf(PACKAGE_USAGE_STATS),
            contract = StartActivityForPermission(PACKAGE_USAGE_STATS) {
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .takeIf { it.resolveActivity(packageManager) != null }
                    ?: Intent(Settings.ACTION_SETTINGS)
            },
            grantChecker = singleGrantChecker(PACKAGE_USAGE_STATS) {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: return@singleGrantChecker false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AppOpsManager.MODE_ALLOWED == appOps.unsafeCheckOpNoThrow("android:get_usage_stats", myUid(), packageName)
                } else {
                    @Suppress("DEPRECATION")
                    AppOpsManager.MODE_ALLOWED == appOps.checkOpNoThrow(
                        "android:get_usage_stats",
                        myUid(),
                        packageName
                    )
                }
            }
        ),
    )
}
