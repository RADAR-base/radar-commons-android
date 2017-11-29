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

import android.content.Intent;
import android.support.annotation.NonNull;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.IOException;

/**
 * RADAR QR Login Manager.
 * Use it by passing a custom AuthStringParser. That parser should parse the string contained in
 * the QR code and parse it to a valid login.
 */
public class QrLoginManager implements LoginManager {
    private final LoginActivity activity;
    private AuthStringParser processor;

    public QrLoginManager(@NonNull LoginActivity activity, @NonNull AuthStringParser processor) {
        this.activity = activity;
        this.processor = processor;
    }

    @Override
    public AppAuthState refresh() {
        return null;
    }

    @Override
    public void start() {
        IntentIntegrator qrIntegrator = new IntentIntegrator(activity);
        qrIntegrator.initiateScan();
    }

    @Override
    public void onActivityCreate() {
        // noop
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() == null) {
                activity.loginFailed(this, null);
            } else {
                try {
                    AppAuthState state = processor.parse(result.getContents());
                    if (state != null) {
                        activity.loginSucceeded(this, state);
                    }
                } catch (IllegalArgumentException | IOException ex) {
                    activity.loginFailed(this, ex);
                }
            }
        }
    }

    @NonNull
    public LoginActivity getActivity() {
        return activity;
    }
}
