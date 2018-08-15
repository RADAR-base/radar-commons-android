package org.radarcns.android.auth.portal;

import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;

import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;

import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.LoginListener;
import org.radarcns.android.auth.LoginManager;
import org.radarcns.producer.AuthenticationException;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.radarcns.android.auth.portal.ManagementPortalService.MANAGEMENT_PORTAL_REFRESH;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON_CONFIGURATION;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON_DISCONNECTED;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON_IO;
import static org.radarcns.android.auth.portal.ManagementPortalService.REQUEST_FAILED_REASON_UNAUTHORIZED;

public class RefreshTokenResultReceiver extends ResultReceiver{
    private final LoginListener loginListener;
    private final LoginManager loginManager;
    private final AtomicBoolean isRefreshing;
    public RefreshTokenResultReceiver(Handler handler , LoginListener listener, LoginManager manager, AtomicBoolean isRefreshing) {
        super(handler);
        this.loginListener = listener;
        this.loginManager = manager;
        this.isRefreshing = isRefreshing;
    }

    @Override
    protected void onReceiveResult(int resultCode, Bundle resultData) {
        if (resultCode == MANAGEMENT_PORTAL_REFRESH) {
            AppAuthState state = AppAuthState.Builder.from(resultData).build();
            loginListener.loginSucceeded(loginManager, state);
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
            loginListener.loginFailed(loginManager, ex);
        }
        isRefreshing.set(false);
    }
}
