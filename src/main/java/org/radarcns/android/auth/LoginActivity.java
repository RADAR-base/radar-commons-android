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
import android.support.annotation.NonNull;

import org.radarcns.android.R;
import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.util.Boast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/** Activity to log in using a variety of login managers. */
public abstract class LoginActivity extends Activity implements LoginListener {
    private static final Logger logger = LoggerFactory.getLogger(LoginActivity.class);
    public static final String ACTION_LOGIN = "org.radarcns.auth.LoginActivity.login";

    private RadarConfiguration config;
    private List<LoginManager> loginManagers;
    private boolean startedFromActivity;

    @Override
    public void onCreate(Bundle savedInstanceBundle) {
        super.onCreate(savedInstanceBundle);
        startedFromActivity = Objects.equals(getIntent().getAction(), ACTION_LOGIN);
        AppAuthState appAuth = AppAuthState.read(this);

        if (appAuth.isValid()) {
            loginSucceeded(null, appAuth);
        } else {
            config = RadarConfiguration.getInstance();
            loginManagers = createLoginManagers();

            if (loginManagers.isEmpty()) {
                throw new IllegalStateException("Cannot use login managers, none are configured.");
            }
            for (LoginManager manager : loginManagers) {
                AppAuthState localState = manager.refresh();
                if (localState != null && localState.isValid()) {
                    loginSucceeded(manager, localState);
                    return;
                }
            }

            for (LoginManager manager : loginManagers) {
                manager.onActivityCreate();
            }
        }
    }

    /**
     * Create your login managers here. Be sure to call the appropriate login manager's start()
     * method if the user indicates that login method
     * @return non-empty list of login managers to use
     */
    @NonNull
    protected abstract List<LoginManager> createLoginManagers();

    @NonNull
    protected abstract Class<? extends Activity> nextActivity();

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        for (LoginManager manager : loginManagers) {
            manager.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void loginFailed(LoginManager manager, Exception ex) {
        logger.error("Failed to log in with {}", manager, ex);
        Boast.makeText(this, R.string.login_failed).show();
    }

    public void loginSucceeded(LoginManager manager, @NonNull AppAuthState appAuthState) {
        appAuthState.store(this);
        if (startedFromActivity) {
            setResult(RESULT_OK, appAuthState.toIntent());
        } else {
            Intent next = new Intent(this, nextActivity());
            startActivity(next);
        }
        finish();
    }

    public RadarConfiguration getConfig() {
        return config;
    }
}
