package org.radarcns.android.auth.portal;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;

import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.LoginListener;
import org.radarcns.android.auth.LoginManager;
import org.radarcns.producer.AuthenticationException;

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

public class ManagementPortalLoginManager implements LoginManager {
    private String refreshToken;
    private String refreshTokenUrl;
    private final LoginListener listener;
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);
    private final ResultReceiver refreshResultReceiver;

    public ManagementPortalLoginManager(LoginListener listener, AppAuthState state) {
        this.listener = listener;
        this.refreshToken = (String)state.getProperty(MP_REFRESH_TOKEN_PROPERTY);
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
                && ManagementPortalService.isEnabled()
                && isRefreshing.compareAndSet(false, true)) {
            ManagementPortalService.requestRefreshToken((Context) listener, refreshTokenUrl, true,
                   refreshResultReceiver);
        }
    }

    @Override
    public AppAuthState refresh() {
        if (refreshToken != null
                && ManagementPortalService.isEnabled()
                && isRefreshing.compareAndSet(false, true)) {
            ManagementPortalService.requestAccessToken((Context)listener, refreshToken, true,
                    refreshResultReceiver);
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
