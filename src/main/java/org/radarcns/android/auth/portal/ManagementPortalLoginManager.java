package org.radarcns.android.auth.portal;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.LoginListener;
import org.radarcns.android.auth.LoginManager;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;
import static org.radarcns.android.auth.portal.ManagementPortalService.MANAGEMENT_PORTAL_REFRESH;

public class ManagementPortalLoginManager implements LoginManager {
    private String refreshToken;
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

    @Override
    public AppAuthState refresh() {
        if (refreshToken != null && ManagementPortalService.isEnabled() && isRefreshing.compareAndSet(false, true)) {
            ManagementPortalService.requestAccessToken((Context)listener, refreshToken, true, new ResultReceiver(new Handler(Looper.getMainLooper())) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    if (resultCode == MANAGEMENT_PORTAL_REFRESH) {
                        AppAuthState state = AppAuthState.Builder.from(resultData).build();
                        listener.loginSucceeded(ManagementPortalLoginManager.this, state);
                    } else {
                        listener.loginFailed(ManagementPortalLoginManager.this, new IOException("Cannot reach management portal"));
                    }
                    isRefreshing.set(false);
                }
            });
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
