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

package org.radarcns.android.auth;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import net.openid.appauth.AuthorizationException;

import org.radarcns.android.R;
import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.util.Boast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LoginActivity extends Activity {
    private static final Logger logger = LoggerFactory.getLogger(LoginActivity.class);
    public static final int LOGIN_OAUTH2_SUCCESS = 12231;

    private RadarConfiguration config;

    protected abstract int fragmentContainer();

    @Override
    public void onCreate(Bundle savedInstanceBundle) {
        super.onCreate(savedInstanceBundle);
        config = RadarConfiguration.getInstance();

        if (getIntent() != null) {
            OAuth2StateManager.getInstance(this).updateAfterAuthorization(this, getIntent());
        }
    }

    public void oauthFailed(AuthorizationException ex) {
        Boast.makeText(LoginActivity.this,
                getString(R.string.retry_oauth_login)).show();
        logger.warn("Log in failed. Please try again.", ex);
    }

    public void loginOAuth2(View view) {
        OAuth2StateManager.getInstance(this).login(this, config);
    }

    public void scanQRCode(View view) {
        IntentIntegrator qrIntegrator = new IntentIntegrator(this);
        qrIntegrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() == null) {
                cancelledQrCode();
            } else {
                processQrCode(result.getContents());
            }
        }
    }

    protected abstract void cancelledQrCode();
    protected abstract void processQrCode(String value);

    public void oauthSucceeded() {
        setResult(LOGIN_OAUTH2_SUCCESS);
        finish();
    }
}
