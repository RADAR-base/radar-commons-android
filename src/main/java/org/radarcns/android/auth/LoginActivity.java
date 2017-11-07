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
import android.support.v4.content.LocalBroadcastManager;
import android.widget.Toast;
import org.radarcns.android.R;
import org.radarcns.android.util.Boast;
import org.radarcns.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;
import java.util.Objects;

import static org.radarcns.android.RadarConfiguration.MANAGEMENT_PORTAL_URL_KEY;
import static org.radarcns.android.RadarConfiguration.RADAR_PREFIX;

/** Activity to log in using a variety of login managers. */
public abstract class LoginActivity extends Activity implements LoginListener {
    private static final Logger logger = LoggerFactory.getLogger(LoginActivity.class);
    public static final String ACTION_LOGIN = "org.radarcns.auth.LoginActivity.login";
    public static final String ACTION_REFRESH = "org.radarcns.auth.LoginActivity.refresh";
    public static final String ACTION_LOGIN_SUCCESS = "org.radarcns.auth.LoginActivity.success";

    private List<LoginManager> loginManagers;
    private boolean startedFromActivity;
    private boolean refreshOnly;
    private AppAuthState appAuth;
    private String managementPortalUrl;
    private ManagementPortalClient mpClient;

    @Override
    protected void onCreate(Bundle savedInstanceBundle) {
        super.onCreate(savedInstanceBundle);
        if (savedInstanceBundle != null) {
            refreshOnly = savedInstanceBundle.getBoolean(ACTION_REFRESH);
            startedFromActivity = savedInstanceBundle.getBoolean(ACTION_LOGIN);
            managementPortalUrl = savedInstanceBundle.getString(MANAGEMENT_PORTAL_URL_KEY);
        } else {
            Intent intent = getIntent();
            String action = intent.getAction();
            refreshOnly = Objects.equals(action, ACTION_REFRESH);
            startedFromActivity = Objects.equals(action, ACTION_LOGIN);
            managementPortalUrl = intent.getStringExtra(RADAR_PREFIX + MANAGEMENT_PORTAL_URL_KEY);
        }

        if (managementPortalUrl != null && !managementPortalUrl.isEmpty()) {
            try {
                mpClient = new ManagementPortalClient(new ServerConfig(managementPortalUrl));
            } catch (MalformedURLException e) {
                logger.error("Cannot create ManagementPortal client from url {}",
                        managementPortalUrl);
                managementPortalUrl = null;
            }
        }

        if (startedFromActivity) {
            Boast.makeText(this, R.string.login_failed, Toast.LENGTH_LONG).show();
        }

        appAuth = AppAuthState.Builder.from(this).build();

        if (appAuth.isValid()) {
            loginSucceeded(null, appAuth);
        } else {
            loginManagers = createLoginManagers(appAuth);

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

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(ACTION_REFRESH, refreshOnly);
        outState.putBoolean(ACTION_LOGIN, startedFromActivity);
        outState.putString(MANAGEMENT_PORTAL_URL_KEY, managementPortalUrl);

        // call superclass to save any view hierarchy
        super.onSaveInstanceState(outState);
    }

    /**
     * Create your login managers here. Call {@link LoginManager#start()} for the login method that
     * a user indicates.
     * @param appAuth previous invalid authentication
     * @return non-empty list of login managers to use
     */
    @NonNull
    protected abstract List<LoginManager> createLoginManagers(AppAuthState appAuth);

    @NonNull
    protected abstract Class<? extends Activity> nextActivity();

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        for (LoginManager manager : loginManagers) {
            manager.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** Call when part of the login procedure failed. */
    public void loginFailed(LoginManager manager, Exception ex) {
        logger.error("Failed to log in with {}", manager, ex);
        Boast.makeText(this, R.string.login_failed, Toast.LENGTH_LONG).show();
    }

    /** Call when the entire login procedure succeeded. */
    public void loginSucceeded(LoginManager manager, @NonNull AppAuthState appAuthState) {
        if (mpClient == null) {
            this.appAuth = appAuthState;
        } else {
            try {
                this.appAuth = mpClient.getSubject(appAuthState);
            } catch (IOException ex) {
                logger.error("Failed to get subject metadata");
                loginFailed(manager, ex);
            }
        }
        this.appAuth.addToPreferences(this);

        LocalBroadcastManager.getInstance(this).sendBroadcast(appAuth.toIntent().setAction(ACTION_LOGIN_SUCCESS));

        if (startedFromActivity) {
            setResult(RESULT_OK, this.appAuth.toIntent());
        } else if (!refreshOnly) {
            Intent next = new Intent(this, nextActivity());
            startActivity(next);
        }
        finish();
    }

    public AppAuthState getAuthState() {
        return appAuth;
    }

    @Override
    protected void onDestroy() {
        if (mpClient != null) {
            mpClient.close();
        }
        super.onDestroy();
    }
}
