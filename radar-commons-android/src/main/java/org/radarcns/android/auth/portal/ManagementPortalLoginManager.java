package org.radarcns.android.auth.portal;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;

import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.LoginActivity;
import org.radarcns.android.auth.LoginManager;
import org.radarcns.producer.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;
import static org.radarcns.android.auth.portal.ManagementPortalService.MANAGEMENT_PORTAL_REFRESH;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON_CONFIGURATION;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON_DISCONNECTED;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON_IO;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON_UNAUTHORIZED;

@SuppressWarnings("unused")
public class ManagementPortalLoginManager implements LoginManager {
    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalLoginManager.class);

    private String refreshToken;
    private String refreshTokenUrl;
    private final LoginActivity listener;
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);
    private final ResultReceiver refreshResultReceiver;

    public ManagementPortalLoginManager(LoginActivity listener, AppAuthState state) {
        this.listener = listener;
        this.refreshToken = state.getAttribute(MP_REFRESH_TOKEN_PROPERTY);
        this.refreshResultReceiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                handleRefreshTokenResult(resultCode, resultData);
            }
        };
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
        refresh();
    }

    public void setTokenFromUrl(String refreshTokenUrl) {
        this.refreshTokenUrl = refreshTokenUrl;
        retrieveRefreshToken();
    }

    private void retrieveRefreshToken() {
        if (refreshTokenUrl != null
                && ManagementPortalService.isEnabled(listener)
                && isRefreshing.compareAndSet(false, true)) {
            try {
                ManagementPortalService.requestRefreshToken(listener, refreshTokenUrl, true,
                        refreshResultReceiver);
            } catch (IllegalStateException ex) {
                listener.loginFailed(this, ex);
            }
        }
    }

    @Override
    public AppAuthState refresh() {
        if (refreshToken != null
                && ManagementPortalService.isEnabled(listener)
                && isRefreshing.compareAndSet(false, true)) {
            try {
                ManagementPortalService.requestAccessToken(listener, refreshToken, true,
                        refreshResultReceiver);
            } catch (IllegalStateException ex) {
                listener.loginFailed(this, ex);
            }
        }
        return null;
    }

    @Override
    public void start() {
        refresh();
    }

    @Override
    public void onActivityCreate() {
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
    }

    private void handleRefreshTokenResult(int resultCode, Bundle resultData) {
        if (resultCode == MANAGEMENT_PORTAL_REFRESH) {
            AppAuthState state = AppAuthState.Builder.from(resultData).build();
            listener.loginSucceeded(ManagementPortalLoginManager.this, state);
        } else {
            int reason = resultData.getInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_IO);
            Exception ex;
            switch (reason) {
                case REQUEST_FAILED_REASON_UNAUTHORIZED:
                    ex = new AuthenticationException("Cannot authenticate");
                    break;
                case REQUEST_FAILED_REASON_CONFIGURATION:
                    ex = new FirebaseRemoteConfigException();
                    break;
                case REQUEST_FAILED_REASON_DISCONNECTED:
                    ex = new ConnectException();
                    break;
                default:
                    ex = new IOException("Cannot reach management portal");
                    break;
            }
            listener.loginFailed(ManagementPortalLoginManager.this, ex);
        }
        isRefreshing.set(false);
    }
}
