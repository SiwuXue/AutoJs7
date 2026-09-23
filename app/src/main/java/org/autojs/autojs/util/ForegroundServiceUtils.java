package org.autojs.autojs.util;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import org.autojs.autojs6.R;

import java.util.List;

/**
 * Created by SuperMonster003 on Apr 10, 2022.
 */
public class ForegroundServiceUtils {

    public static int FOREGROUND_SERVICE_TYPE_UNKNOWN = -33127;

    public static void createNotificationChannelIfNeeded(Context context, Class<?> className, String name, @Nullable String description) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = getChannelId(className);

            // IMPORTANCE_LOW rather than IMPORTANCE_MIN: a MIN notification is
            // hidden from the shade, and EMUI/HarmonyOS treats a foreground
            // service without a visible notification as expendable -- its
            // background manager stops the service seconds after it starts.
            // LOW keeps the notification silent but visible, which is exactly
            // what a persistent service notification is for.
            // zh-CN: 使用 IMPORTANCE_LOW 而非 IMPORTANCE_MIN: MIN 级通知不会出现在
            // 通知栏, 而 EMUI/HarmonyOS 会把"没有可见通知"的前台服务视为可回收对象
            // —— 其后台管控系统会在服务启动数秒后将其停止.
            // LOW 保持静音但可见, 这正是常驻服务通知应有的表现.
            NotificationChannel channel = new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW);

            if (description == null) {
                channel.setDescription(name);
            } else {
                channel.setDescription(description);
            }

            channel.enableLights(false);

            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    public static Notification getNotification(Context context, Intent intent, Class<?> className, String title, String content) {
        return getNotification(context, intent, className, title, content, null);
    }

    /**
     * Builds the persistent notification for a foreground service.
     *
     * @param actions optional action buttons, or null for none.
     */
    public static Notification getNotification(Context context, Intent intent, Class<?> className, String title, String content, @Nullable List<NotificationCompat.Action> actions) {
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        String channelId = getChannelId(className);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(content)
                // Without an explicit big-text style a multi-line body is collapsed
                // to its first line, which would hide the second endpoint.
                // zh-CN: 若不显式声明大文本样式, 多行内容会被折叠为第一行,
                // 从而隐藏第二个端点地址.
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setContentIntent(contentIntent)
                .setSmallIcon(R.drawable.autojs6_status_bar_icon)
                .setAutoCancel(true)
                .setOngoing(true)
                .setSilent(true)
                .setWhen(System.currentTimeMillis())
                .setChannelId(channelId);

        if (actions != null) {
            for (NotificationCompat.Action action : actions) {
                builder.addAction(action);
            }
        }

        Notification notification = builder.build();

        notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;

        return notification;
    }

    @NonNull
    public static String getChannelId(Class<?> clazz) {
        // The `_v2` suffix is deliberate. A notification channel's importance is
        // immutable once the channel exists: raising it in code has no effect on
        // a channel the system already knows about, and the user is the only one
        // who can promote it afterwards. A brand new id is therefore the only way
        // to hand out a channel created with the corrected importance. The old
        // channel is left behind (unused) rather than deleted, so nothing the
        // user configured on it is silently destroyed.
        // zh-CN: `_v2` 后缀是刻意为之. 通知渠道一旦存在, 其重要性便不可变:
        // 在代码里调高对系统已知的渠道毫无作用, 之后只有用户能手动提升它.
        // 因此唯一能让修正后的重要性生效的办法, 就是启用一个全新的 id.
        // 旧渠道保留(不再使用)而不删除, 以免静默销毁用户在其上做过的任何配置.
        return clazz.getName() + ".foreground_v2";
    }

    /**
     * @noinspection deprecation
     */
    public static boolean isRunning(Context context, Class<?> clazz) {
        ActivityManager manager = (ActivityManager) context.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (clazz.getName().equals(service.service.getClassName())) {
                return service.foreground;
            }
        }
        return false;
    }

}
