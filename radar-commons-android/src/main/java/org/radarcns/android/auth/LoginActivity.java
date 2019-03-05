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
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

import org.radarcns.android.R;
import org.radarcns.android.RadarApplication;
import org.radarcns.android.util.Boast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Activity to log in using a variety of login managers. */
public abstract class LoginActivity extends AppCompatActivity implements LoginListener {
    private static final Logger logger = LoggerFactory.getLogger(LoginActivity.class);
    public static final String ACTION_LOGIN = "org.radarcns.auth.LoginActivity.login";
    public static final String ACTION_REFRESH = "org.radarcns.auth.LoginActivity.refresh";
    public static final String ACTION_LOGIN_SUCCESS = "org.radarcns.auth.LoginActivity.success";

    private boolean startedFromActivity;
    private boolean refreshOnly;

    protected AuthServiceConnection authConnection;

    @Override
    protected void onCreate(Bundle savedInstanceBundle) {
        super.onCreate(savedInstanceBundle);

        if (savedInstanceBundle != null) {
            refreshOnly = savedInstanceBundle.getBoolean(ACTION_REFRESH);
            startedFromActivity = savedInstanceBundle.getBoolean(ACTION_LOGIN);
        } else {
            Intent intent = getIntent();
            String action = intent.getAction();
            refreshOnly = Objects.equals(action, ACTION_REFRESH);
            startedFromActivity = Objects.equals(action, ACTION_LOGIN);
        }

        if (startedFromActivity) {
            Boast.makeText(this, R.string.login_failed, Toast.LENGTH_LONG).show();
        }

        authConnection = new AuthServiceConnection(this, this);
        authConnection.getOnBoundListeners().add(0, binder -> {
                    binder.setInLoginActivity(true);
                    return null;
                });
        authConnection.getOnBoundListeners().add(binder -> {
            binder.refresh();
            for (LoginManager manager : binder.getManagers()) {
                if (manager.onActivityCreate(LoginActivity.this)) {
                    binder.update(manager);
                    break;
                }
            }
            return null;
        });
        authConnection.getOnUnboundListeners().add(binder -> {
            binder.setInLoginActivity(false);
            return null;
        });
        authConnection.bind();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        authConnection.unbind();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(ACTION_REFRESH, refreshOnly);
        outState.putBoolean(ACTION_LOGIN, startedFromActivity);

        // call superclass to save any view hierarchy
        super.onSaveInstanceState(outState);
    }

    /** Call when part of the login procedure failed. */
    public void loginFailed(LoginManager manager, Exception ex) {
        logger.error("Failed to log in with {}", manager, ex);
        runOnUiThread(() -> Boast.makeText(
                LoginActivity.this, R.string.login_failed, Toast.LENGTH_LONG).show());
    }

    /** Call when the entire login procedure succeeded. */
    public void loginSucceeded(LoginManager manager, @NonNull AppAuthState appAuthState) {
        logger.info("Login succeeded");

        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(appAuthState.toIntent().setAction(ACTION_LOGIN_SUCCESS));

        if (startedFromActivity) {
            logger.debug("Start next activity with result");
            setResult(RESULT_OK, appAuthState.toIntent());
        } else if (!refreshOnly) {
            logger.debug("Start next activity without result");
            Intent next = new Intent(this, ((RadarApplication)getApplication()).getMainActivity());
            next.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
            Bundle extras = new Bundle();
            appAuthState.addToBundle(extras);
            next.putExtras(extras);
            startActivity(next);
        }
        finish();
    }
}
