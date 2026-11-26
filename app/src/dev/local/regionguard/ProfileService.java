package dev.local.regionguard;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Receives the framework binder for the module app's control and settings entry points. */
final class ProfileService implements XposedServiceHelper.OnServiceListener {
    private static final long BIND_TIMEOUT_MS = 5_000;
    private static final long REPLY_GRACE_MS = 100;
    private static final long NOTICE_GRACE_MS = 250;
    private static final ProfileService CONNECTION = new ProfileService();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable recycle = this::recycleIfIdle;
    private XposedService service;
    private IllegalStateException failure;
    private int pendingCalls;
    private Runnable visibleNotice;
    private boolean noticeShown;

    private ProfileService() {
        XposedServiceHelper.registerListener(this);
    }

    static XposedService await() throws InterruptedException {
        return CONNECTION.awaitService();
    }

    private synchronized XposedService awaitService() throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + BIND_TIMEOUT_MS;
        while (service == null) {
            if (failure != null) throw failure;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw loseConnection(null, "Timed out waiting for the LSPosed configuration service", null);
            wait(remaining);
        }
        return service;
    }

    @Override public synchronized void onServiceBind(XposedService connected) {
        service = connected;
        failure = null;
        noticeShown = false;
        main.removeCallbacks(recycle);
        notifyAll();
    }

    @Override public synchronized void onServiceDied(XposedService disconnected) {
        if (service == disconnected) loseConnection(disconnected, "LSPosed configuration service disconnected", null);
    }

    static IllegalStateException discard(XposedService service, String reason, Throwable cause) {
        return CONNECTION.loseConnection(service, reason, cause);
    }

    private synchronized IllegalStateException loseConnection(XposedService expected, String reason, Throwable cause) {
        if (failure != null) return failure;
        if (service != expected) return new IllegalStateException(reason, cause);
        service = null;
        failure = new IllegalStateException(reason + ". RegionGuard restarting pid=" + Process.myPid(), cause);
        notifyAll();
        scheduleRecycle();
        return failure;
    }

    static void confirmConnected(XposedService expected) {
        synchronized (CONNECTION) {
            if (CONNECTION.failure != null) throw CONNECTION.failure;
            if (CONNECTION.service != expected) throw new IllegalStateException("LSPosed configuration connection changed during the request");
        }
    }

    static void callStarted() {
        synchronized (CONNECTION) { CONNECTION.pendingCalls++; }
    }

    static void callFinished() {
        synchronized (CONNECTION) {
            CONNECTION.pendingCalls--;
            CONNECTION.scheduleRecycle();
        }
    }

    static void setVisibleNotice(Runnable notice, boolean visible) {
        synchronized (CONNECTION) {
            if (visible) CONNECTION.visibleNotice = notice;
            else if (CONNECTION.visibleNotice == notice) CONNECTION.visibleNotice = null;
        }
    }

    // libxposed only pushes a binder. Retire this process after replies have had time to leave it.
    private void scheduleRecycle() {
        if (failure == null || pendingCalls != 0) return;
        main.removeCallbacks(recycle);
        main.postDelayed(recycle, REPLY_GRACE_MS);
    }

    private void recycleIfIdle() {
        Runnable notice;
        synchronized (this) {
            if (failure == null || pendingCalls != 0) return;
            notice = noticeShown ? null : visibleNotice;
            if (notice == null) {
                Log.w("RegionGuard", "[RegionGuard] " + failure.getMessage());
                Process.killProcess(Process.myPid());
                return;
            }
            noticeShown = true;
        }
        try {
            notice.run();
        } finally {
            main.postDelayed(recycle, NOTICE_GRACE_MS);
        }
    }
}
