package org.radarcns.android.auth.portal;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.LoginListener;
import org.radarcns.android.auth.LoginManager;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;

public class ManagementPortalLoginManager implements LoginManager {
    private String refreshToken;
    private String refreshTokenUrl;
    private final LoginListener listener;
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);

    public ManagementPortalLoginManager(LoginListener listener, AppAuthState state) {
        this.listener = listener;
        this.refreshToken = (String)state.getProperty(MP_REFRESH_TOKEN_PROPERTY);
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
        refresh();
    }

    public void setTokenFromUrl(String refreshTokenUrl) {
        this.refreshTokenUrl = refreshTokenUrl;
        retrieveRefreshToken();
    }

    public AppAuthState retrieveRefreshToken() {
        if(this.refreshTokenUrl != null
                && refreshToken == null
                && ManagementPortalService.isEnabled()
                && isRefreshing.compareAndSet(false, true)) {
            ManagementPortalService.requestRefreshToken((Context) listener, refreshTokenUrl, true,
                    new RefreshTokenResultReceiver(new Handler(Looper.getMainLooper()),
                            listener,
                            ManagementPortalLoginManager.this,
                            isRefreshing));
        }
        return null;
    }

    @Override
    public AppAuthState refresh() {
        if (refreshToken != null
                && ManagementPortalService.isEnabled()
                && isRefreshing.compareAndSet(false, true)) {
            ManagementPortalService.requestAccessToken((Context)listener, refreshToken, true,
                    new RefreshTokenResultReceiver(new Handler(Looper.getMainLooper()),
                            listener,
                            ManagementPortalLoginManager.this,
                            isRefreshing));
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
}
