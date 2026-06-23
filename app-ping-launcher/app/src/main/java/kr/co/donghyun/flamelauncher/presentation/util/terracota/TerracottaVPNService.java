/*
 * Terracotta VpnService — ported for FlameLauncher.
 *
 * Original: ZalithLauncher2 (GPL-3.0), modified from FoldCraftLauncher.
 *   https://github.com/FCL-Team/FoldCraftLauncher/blob/5926006/FCL/src/main/java/com/tungsten/fcl/terracotta/TerracottaVPNService.java
 *
 * 수정(FlameLauncher 이식):
 *  - 패키지: kr.co.donghyun.flamelauncher.presentation.util.terracota (실제 디렉터리 기준)
 *  - TerracottaAndroidAPI 는 kr.co.donghyun.terracota 에 있어 import
 *  - Terracotta / TerracottaState 는 같은 패키지라 import 불필요
 *  - Terracotta.setWaiting() 인자 제거 (이식본 시그니처)
 *  - NotificationChannelData 의존 제거 → 자체 CHANNEL_ID + ensureChannel()
 *  - NOTIFICATION_ID_VPN_REQUEST_CODE 제거 → 고정 request code(1001)
 */

package kr.co.donghyun.flamelauncher.presentation.util.terracota;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

import kr.co.donghyun.flamelauncher.R;
import kr.co.donghyun.terracota.TerracottaAndroidAPI;

/**
 * [Modified from FCL](https://github.com/FCL-Team/FoldCraftLauncher/blob/5926006/FCL/src/main/java/com/tungsten/fcl/terracotta/TerracottaVPNService.java)
 */
@SuppressLint("VpnServicePolicy")
public class TerracottaVPNService extends VpnService {

    private static final String TAG = "TerracottaVPNService";

    private static final int VPN_NOTIFICATION_ID = 1;

    /** 포그라운드 알림 채널 id (FlameLauncher 자체) */
    public static final String CHANNEL_ID = "flame_terracotta_vpn";

    public static final String ACTION_START        = "kr.co.donghyun.flamelauncher.terracotta.action.START";
    public static final String ACTION_STOP         = "kr.co.donghyun.flamelauncher.terracotta.action.STOP";
    public static final String ACTION_REPOST       = "kr.co.donghyun.flamelauncher.terracotta.action.REPOST";
    public static final String ACTION_UPDATE_STATE = "kr.co.donghyun.flamelauncher.terracotta.action.UPDATE_STATE";

    private static final String EXTRA_FROM_DELETE = "from_delete";
    public static final String EXTRA_STATE_TEXT   = "terracotta_state_text";

    private NotificationManager notificationManager;
    private int currentStateStringRes = -1;
    private volatile boolean isStopping = false;

    private ParcelFileDescriptor vpnInterface;

    private static boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;

        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand, action = " + action);

        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            ensureChannel();
        }

        if (ACTION_STOP.equals(action)) {
            isStopping = true;
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        if (ACTION_UPDATE_STATE.equals(action)) {
            currentStateStringRes = getStateTextRes(intent);

            if (!isStopping) {
                Notification n = buildVpnNotification();
                if (n != null) notificationManager.notify(VPN_NOTIFICATION_ID, n);
            }
            return Service.START_STICKY;
        }

        boolean fromDelete = intent != null && intent.getBooleanExtra(EXTRA_FROM_DELETE, false);

        if (ACTION_REPOST.equals(action) && fromDelete && !isStopping) {
            Log.d(TAG, "Repost VPN notification after user cleared it.");
            currentStateStringRes = getStateTextRes(intent);
            Notification notification = buildVpnNotification();
            if (notification == null)
                return Service.START_NOT_STICKY;

            startForeground0(notification);
            return Service.START_STICKY;
        }

        isStopping = false;

        Notification notification = buildVpnNotification();
        if (notification == null)
            return Service.START_NOT_STICKY;

        startForeground0(notification);

        Builder vpnBuilder = new Builder().setSession("Terracotta Connection");

        try {
            vpnBuilder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        TerracottaAndroidAPI.VpnServiceRequest request = TerracottaAndroidAPI.getPendingVpnServiceRequest();
        vpnInterface = request.startVpnService(vpnBuilder);

        return Service.START_STICKY;
    }

    @Override
    public void onRevoke() {
        Log.w(TAG, "onRevoke(): preempted by another VPN or revoked by user; tearing down.");
        isStopping = true;
        Terracotta.INSTANCE.setWaiting();
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy(): vpn service finished");
        isStopping = true;
        Terracotta.INSTANCE.setWaiting();
        cleanup();
        super.onDestroy();
    }

    private int getStateTextRes(Intent intent) {
        int res = -1;
        if (intent != null && intent.hasExtra(EXTRA_STATE_TEXT)) {
            res = intent.getIntExtra(EXTRA_STATE_TEXT, -1);
        }
        return res;
    }

    private void ensureChannel() {
        NotificationChannel channel = notificationManager.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.terracotta_notification_title),
                    NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildVpnNotification() {
        Terracotta.Mode mode = Terracotta.INSTANCE.getMode();
        if (mode == null) return null;

        String title = getString(R.string.terracotta_notification_title);
        String modeText = mode == Terracotta.Mode.Host
                ? getString(R.string.terracotta_player_kind_host)
                : getString(R.string.terracotta_player_kind_guest);

        if (currentStateStringRes == -1) {
            TerracottaState.Ready state = Terracotta.INSTANCE.getState().getValue();
            if (state != null && !(state instanceof TerracottaState.Waiting)) {
                currentStateStringRes = state.localStringRes();
            }
        }
        String stateString = (currentStateStringRes == -1) ? "Unknown" : getString(currentStateStringRes);
        String contentText = String.format(getString(R.string.terracotta_notification_desc), modeText, stateString);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);

        Intent deleteIntent = new Intent(this, TerracottaVPNService.class)
                .setAction(ACTION_REPOST)
                .putExtra(EXTRA_FROM_DELETE, true)
                .putExtra(EXTRA_STATE_TEXT, currentStateStringRes);
        PendingIntent deletePendingIntent = PendingIntent.getService(
                this,
                1001,
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(contentText)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setDeleteIntent(deletePendingIntent);

        return builder.build();
    }

    private void startForeground0(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(VPN_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(VPN_NOTIFICATION_ID, notification);
        }
    }

    private void cleanup() {
        Log.d(TAG, "cleanup(): close tun & cancel notification");

        if (notificationManager != null) {
            notificationManager.cancel(VPN_NOTIFICATION_ID);
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
            }
            vpnInterface = null;
        }

        running = false;
    }
}