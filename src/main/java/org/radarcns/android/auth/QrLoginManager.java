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

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public abstract class QrLoginManager implements LoginManager {
    private final LoginActivity activity;
    private AuthStringProcessor processor;

    public QrLoginManager(LoginActivity activity, AuthStringProcessor processor) {
        this.activity = activity;
        this.processor = processor;
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
                AppAuthState state = processor.process(result.getContents());
                if (state != null) {
                    activity.loginSucceeded(this, state);
                } else {
                    activity.loginFailed(this, null);
                }
            }
        }
    }

    public LoginActivity getActivity() {
        return activity;
    }
}
