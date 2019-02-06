package org.radarcns.android.auth.portal;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.LoginActivity;
import org.radarcns.android.auth.LoginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static android.content.Context.BIND_AUTO_CREATE;
import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;

@SuppressWarnings("unused")
public class ManagementPortalLoginManager implements LoginManager {
    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalLoginManager.class);
    private final ServiceConnection serviceConnection;
    private boolean doRefresh;
    private boolean doRetrieve;

    private String refreshToken;
    private String refreshTokenUrl;
    private final LoginActivity listener;
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);
    private ManagementPortalService.ManagementPortalBinder managementPortalBinder;

    public ManagementPortalLoginManager(LoginActivity listener, AppAuthState state) {
        this.listener = listener;
        this.refreshToken = state.getAttribute(MP_REFRESH_TOKEN_PROPERTY);
        this.managementPortalBinder = null;
        this.doRetrieve = false;
        this.doRefresh = false;
        this.serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                managementPortalBinder = (ManagementPortalService.ManagementPortalBinder) service;
                if (doRefresh) {
                    retrieveRefreshToken();
                }
                if (doRetrieve) {
                    refresh();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                managementPortalBinder = null;
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
            if (managementPortalBinder != null) {
                doRetrieve = false;
                managementPortalBinder.initialize(refreshTokenUrl,
                        auth -> listener.loginSucceeded(this, auth),
                        ex -> listener.loginFailed(this, ex));
            } else {
                doRetrieve = true;
                connect();
            }
        }
    }

    private void connect() {
        Intent intent = new Intent(listener, ManagementPortalService.class);
        try {
            listener.bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        } catch (IllegalStateException ex) {
            logger.error("Cannot bind to ManagementPortalService");
        }
    }

    @Override
    public AppAuthState refresh() {
        if (refreshToken != null
                && ManagementPortalService.isEnabled(listener)
                && isRefreshing.compareAndSet(false, true)) {
            if (managementPortalBinder != null) {
                doRefresh = false;
                managementPortalBinder.refresh(refreshToken, false,
                        auth -> listener.loginSucceeded(this, auth),
                        ex -> listener.loginFailed(this, ex));
            } else {
                doRefresh = true;
                connect();
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
}
