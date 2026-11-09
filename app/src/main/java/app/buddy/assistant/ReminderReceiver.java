package app.buddy.assistant;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * A voice reminder ("напомни через 10 минут позвонить маме"): an alarm at that time, then a notification with the
 * text. Kept by Android's alarm service only, so a phone restart before then forgets it.
 */
public class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "reminders";

    static void schedule(Context c, long at, String text) {
        int id = (int) (at / 1000 % Integer.MAX_VALUE);
        Intent i = new Intent(c, ReminderReceiver.class).putExtra("text", text).putExtra("id", id);
        PendingIntent pi = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); // a few minutes late at worst
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String text = intent.getStringExtra("text");
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.vc_reminder_channel),
                NotificationManager.IMPORTANCE_HIGH));
        nm.notify(8000 + intent.getIntExtra("id", 0) % 1000, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(R.string.vc_reminder_title)).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true).build());
    }
}
