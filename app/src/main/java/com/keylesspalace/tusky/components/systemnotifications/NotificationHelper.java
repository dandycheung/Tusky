/* Copyright 2018 Jeremiasz Nelz <remi6397(a)gmail.com>
 * Copyright 2017 Andrew Dawson
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.components.systemnotifications;

import static com.keylesspalace.tusky.BuildConfig.APPLICATION_ID;
import static com.keylesspalace.tusky.util.StatusParsingHelper.parseAsMastodonHtml;
import static com.keylesspalace.tusky.viewdata.PollViewDataKt.buildDescription;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import androidx.core.app.TaskStackBuilder;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.FutureTarget;
import com.keylesspalace.tusky.MainActivity;
import com.keylesspalace.tusky.R;
import com.keylesspalace.tusky.components.compose.ComposeActivity;
import com.keylesspalace.tusky.db.entity.AccountEntity;
import com.keylesspalace.tusky.db.AccountManager;
import com.keylesspalace.tusky.entity.Notification;
import com.keylesspalace.tusky.entity.Poll;
import com.keylesspalace.tusky.entity.PollOption;
import com.keylesspalace.tusky.entity.Status;
import com.keylesspalace.tusky.receiver.SendStatusBroadcastReceiver;
import com.keylesspalace.tusky.util.StringUtils;
import com.keylesspalace.tusky.viewdata.PollViewDataKt;
import com.keylesspalace.tusky.worker.NotificationWorker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class NotificationHelper {

    /** ID of notification shown when fetching notifications */
    public static final int NOTIFICATION_ID_FETCH_NOTIFICATION = 0;
    /** ID of notification shown when pruning the cache */
    public static final int NOTIFICATION_ID_PRUNE_CACHE = 1;
    /** Dynamic notification IDs start here */
    private static int notificationId = NOTIFICATION_ID_PRUNE_CACHE + 1;

    private static final String TAG = "NotificationHelper";

    public static final String REPLY_ACTION = "REPLY_ACTION";

    public static final String KEY_REPLY = "KEY_REPLY";

    public static final String KEY_SENDER_ACCOUNT_ID = "KEY_SENDER_ACCOUNT_ID";

    public static final String KEY_SENDER_ACCOUNT_IDENTIFIER = "KEY_SENDER_ACCOUNT_IDENTIFIER";

    public static final String KEY_SENDER_ACCOUNT_FULL_NAME = "KEY_SENDER_ACCOUNT_FULL_NAME";

    public static final String KEY_SERVER_NOTIFICATION_ID = "KEY_SERVER_NOTIFICATION_ID";

    public static final String KEY_CITED_STATUS_ID = "KEY_CITED_STATUS_ID";

    public static final String KEY_VISIBILITY = "KEY_VISIBILITY";

    public static final String KEY_SPOILER = "KEY_SPOILER";

    public static final String KEY_MENTIONS = "KEY_MENTIONS";

    /**
     * notification channels used on Android O+
     **/
    public static final String CHANNEL_MENTION = "CHANNEL_MENTION";
    public static final String CHANNEL_FOLLOW = "CHANNEL_FOLLOW";
    public static final String CHANNEL_FOLLOW_REQUEST = "CHANNEL_FOLLOW_REQUEST";
    public static final String CHANNEL_BOOST = "CHANNEL_BOOST";
    public static final String CHANNEL_FAVOURITE = "CHANNEL_FAVOURITE";
    public static final String CHANNEL_POLL = "CHANNEL_POLL";
    public static final String CHANNEL_SUBSCRIPTIONS = "CHANNEL_SUBSCRIPTIONS";
    public static final String CHANNEL_SIGN_UP = "CHANNEL_SIGN_UP";
    public static final String CHANNEL_UPDATES = "CHANNEL_UPDATES";
    public static final String CHANNEL_REPORT = "CHANNEL_REPORT";
    public static final String CHANNEL_BACKGROUND_TASKS = "CHANNEL_BACKGROUND_TASKS";

    /**
     * WorkManager Tag
     */
    private static final String NOTIFICATION_PULL_TAG = "pullNotifications";

    /** Tag for the summary notification */
    private static final String GROUP_SUMMARY_TAG = APPLICATION_ID + ".notification.group_summary";

    /** The name of the account that caused the notification, for use in a summary */
    private static final String EXTRA_ACCOUNT_NAME = APPLICATION_ID + ".notification.extra.account_name";

    /** The notification's type (string representation of a Notification.Type) */
    private static final String EXTRA_NOTIFICATION_TYPE = APPLICATION_ID + ".notification.extra.notification_type";

    /**
     * Takes a given Mastodon notification and creates a new Android notification or updates the
     * existing Android notification.
     * <p>
     * The Android notification has it's tag set to the Mastodon notification ID, and it's ID set
     * to the ID of the account that received the notification.
     *
     * @param context to access application preferences and services
     * @param body    a new Mastodon notification
     * @param account the account for which the notification should be shown
     * @return the new notification
     */
    @NonNull
    public static NotificationManagerCompat.NotificationWithIdAndTag make(final @NonNull Context context, @NonNull NotificationManager notificationManager, @NonNull Notification body, @NonNull AccountEntity account) {
        return new NotificationManagerCompat.NotificationWithIdAndTag(
            body.getId(),
            (int)account.getId(),
            NotificationHelper.makeBaseNotification(context, notificationManager, body, account)
        );
    }

        @NonNull
    public static android.app.Notification makeBaseNotification(final @NonNull Context context, @NonNull NotificationManager notificationManager, @NonNull Notification body, @NonNull AccountEntity account) {
        body = body.rewriteToStatusTypeIfNeeded(account.getAccountId());
        String mastodonNotificationId = body.getId();
        int accountId = (int) account.getId();

        // Check for an existing notification with this Mastodon Notification ID
        android.app.Notification existingAndroidNotification = null;
        StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();
        for (StatusBarNotification androidNotification : activeNotifications) {
            if (mastodonNotificationId.equals(androidNotification.getTag()) && accountId == androidNotification.getId()) {
                existingAndroidNotification = androidNotification.getNotification();
            }
        }

        notificationId++;
        // Create the notification -- either create a new one, or use the existing one.
        NotificationCompat.Builder builder;
        if (existingAndroidNotification == null) {
            builder = newAndroidNotification(context, body, account);
        } else {
            builder = new NotificationCompat.Builder(context, existingAndroidNotification);
        }

        builder.setContentTitle(titleForType(context, body, account))
                .setContentText(bodyForType(body, context, account.getAlwaysOpenSpoiler()));

        if (body.getType() == Notification.Type.MENTION || body.getType() == Notification.Type.POLL) {
            builder.setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(bodyForType(body, context, account.getAlwaysOpenSpoiler())));
        }

        //load the avatar synchronously
        Bitmap accountAvatar;
        try {
            FutureTarget<Bitmap> target = Glide.with(context)
                    .asBitmap()
                    .load(body.getAccount().getAvatar())
                    .transform(new RoundedCorners(20))
                    .submit();

            accountAvatar = target.get();
        } catch (ExecutionException | InterruptedException e) {
            Log.d(TAG, "error loading account avatar", e);
            accountAvatar = BitmapFactory.decodeResource(context.getResources(), R.drawable.avatar_default);
        }

        builder.setLargeIcon(accountAvatar);

        // Reply to mention action; RemoteInput is available from KitKat Watch, but buttons are available from Nougat
        if (body.getType() == Notification.Type.MENTION) {
            RemoteInput replyRemoteInput = new RemoteInput.Builder(KEY_REPLY)
                    .setLabel(context.getString(R.string.label_quick_reply))
                    .build();

            PendingIntent quickReplyPendingIntent = getStatusReplyIntent(context, body, account);

            NotificationCompat.Action quickReplyAction =
                    new NotificationCompat.Action.Builder(R.drawable.ic_reply_24dp,
                            context.getString(R.string.action_quick_reply),
                            quickReplyPendingIntent)
                            .addRemoteInput(replyRemoteInput)
                            .build();

            builder.addAction(quickReplyAction);

            PendingIntent composeIntent = getStatusComposeIntent(context, body, account);

            NotificationCompat.Action composeAction =
                    new NotificationCompat.Action.Builder(R.drawable.ic_reply_24dp,
                            context.getString(R.string.action_compose_shortcut),
                            composeIntent)
                            .setShowsUserInterface(true)
                            .build();

            builder.addAction(composeAction);
        }

        builder.setSubText(account.getFullName());
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        builder.setCategory(NotificationCompat.CATEGORY_SOCIAL);
        builder.setOnlyAlertOnce(true);

        Bundle extras = new Bundle();
        // Add the sending account's name, so it can be used when summarising this notification
        extras.putString(EXTRA_ACCOUNT_NAME, body.getAccount().getName());
        extras.putString(EXTRA_NOTIFICATION_TYPE, body.getType().name());
        builder.addExtras(extras);

        // Only ever alert for the summary notification
        builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY);

        return builder.build();
    }

    /**
     * Creates the summary notifications for a notification type.
     * <p>
     * Notifications are sent to channels. Within each channel they are grouped and the group has a summary.
     * <p>
     * Tusky uses N notification channels for each account, each channel corresponds to a type
     * of notification (follow, reblog, mention, etc). Each channel also has a
     * summary notifications along with its regular notifications.
     * <p>
     * The group key is the same as the channel ID.
     *
     * @see <a href="https://developer.android.com/develop/ui/views/notifications/group">Create a
     * notification group</a>
     */
    public static NotificationManagerCompat.NotificationWithIdAndTag makeSummaryNotification(@NonNull Context context, @NonNull NotificationManager notificationManager, @NonNull AccountEntity account, @NonNull Notification.Type type, @NonNull List<Notification> additionalNotifications) {
        int accountId = (int) account.getId();

        String typeChannelId = getChannelId(account, type);

        if (typeChannelId == null) {
            return null;
        }

        // Create a notification that summarises the other notifications in this group
        //
        // NOTE: We always create a summary notification (even for activeNotificationsForType.size() == 1):
        //   - No need to especially track the grouping
        //   - No need to change an existing single notification when there arrives another one of its group
        //   - Only the summary one will get announced
        TaskStackBuilder summaryStackBuilder = TaskStackBuilder.create(context);
        summaryStackBuilder.addParentStack(MainActivity.class);
        Intent summaryResultIntent = MainActivity.openNotificationIntent(context, accountId, type);
        summaryStackBuilder.addNextIntent(summaryResultIntent);

        PendingIntent summaryResultPendingIntent = summaryStackBuilder.getPendingIntent((int) (notificationId + account.getId() * 10000),
            pendingIntentFlags(false));

        List<StatusBarNotification> activeNotifications = getActiveNotifications(notificationManager.getActiveNotifications(), accountId, typeChannelId);

        int notificationCount = activeNotifications.size() + additionalNotifications.size();

        String title = context.getResources().getQuantityString(R.plurals.notification_title_summary, notificationCount, notificationCount);
        String text = joinNames(context, activeNotifications, additionalNotifications);

        NotificationCompat.Builder summaryBuilder = new NotificationCompat.Builder(context, typeChannelId)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(summaryResultPendingIntent)
            .setColor(context.getColor(R.color.notification_color))
            .setAutoCancel(true)
            .setShortcutId(Long.toString(account.getId()))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(account.getFullName())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(typeChannelId)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            ;

        setSoundVibrationLight(account, summaryBuilder);

        String summaryTag = GROUP_SUMMARY_TAG + "." + typeChannelId;

        return new NotificationManagerCompat.NotificationWithIdAndTag(summaryTag, accountId, summaryBuilder.build());
    }

    private static List<StatusBarNotification> getActiveNotifications(StatusBarNotification[] allNotifications, int accountId, String typeChannelId) {
        // Return all active notifications, ignoring notifications that:
        // - belong to a different account
        // - belong to a different type
        // - are summary notifications
        List<StatusBarNotification> activeNotificationsForType = new ArrayList<>();
        for (StatusBarNotification sn : allNotifications) {
            if (sn.getId() != accountId)
                continue;

            String channelId = sn.getNotification().getGroup();

            if (!channelId.equals(typeChannelId))
                continue;

            String summaryTag = GROUP_SUMMARY_TAG + "." + channelId;
            if (summaryTag.equals(sn.getTag()))
                continue;

            activeNotificationsForType.add(sn);
        }

        return activeNotificationsForType;
    }

    private static NotificationCompat.Builder newAndroidNotification(Context context, Notification body, AccountEntity account) {

        Intent eventResultIntent = MainActivity.openNotificationIntent(context, account.getId(), body.getType());

        TaskStackBuilder eventStackBuilder = TaskStackBuilder.create(context);
        eventStackBuilder.addParentStack(MainActivity.class);
        eventStackBuilder.addNextIntent(eventResultIntent);

        PendingIntent eventResultPendingIntent = eventStackBuilder.getPendingIntent((int) account.getId(),
                pendingIntentFlags(false));

        String channelId = getChannelId(account, body);
        assert channelId != null;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentIntent(eventResultPendingIntent)
                .setColor(context.getColor(R.color.notification_color))
                .setGroup(channelId)
                .setAutoCancel(true)
                .setShortcutId(Long.toString(account.getId()))
                .setDefaults(0); // So it doesn't ring twice, notify only in Target callback

        setSoundVibrationLight(account, builder);

        return builder;
    }

    private static PendingIntent getStatusReplyIntent(Context context, Notification body, AccountEntity account) {
        Status status = body.getStatus();

        String inReplyToId = status.getId();
        Status actionableStatus = status.getActionableStatus();
        Status.Visibility replyVisibility = actionableStatus.getVisibility();
        String contentWarning = actionableStatus.getSpoilerText();
        List<Status.Mention> mentions = actionableStatus.getMentions();
        List<String> mentionedUsernames = new ArrayList<>();
        mentionedUsernames.add(actionableStatus.getAccount().getUsername());
        for (Status.Mention mention : mentions) {
            mentionedUsernames.add(mention.getUsername());
        }
        mentionedUsernames.removeAll(Collections.singleton(account.getUsername()));
        mentionedUsernames = new ArrayList<>(new LinkedHashSet<>(mentionedUsernames));

        Intent replyIntent = new Intent(context, SendStatusBroadcastReceiver.class)
                .setAction(REPLY_ACTION)
                .putExtra(KEY_SENDER_ACCOUNT_ID, account.getId())
                .putExtra(KEY_SENDER_ACCOUNT_IDENTIFIER, account.getIdentifier())
                .putExtra(KEY_SENDER_ACCOUNT_FULL_NAME, account.getFullName())
                .putExtra(KEY_SERVER_NOTIFICATION_ID, body.getId())
                .putExtra(KEY_CITED_STATUS_ID, inReplyToId)
                .putExtra(KEY_VISIBILITY, replyVisibility)
                .putExtra(KEY_SPOILER, contentWarning)
                .putExtra(KEY_MENTIONS, mentionedUsernames.toArray(new String[0]));

        return PendingIntent.getBroadcast(context.getApplicationContext(),
                notificationId,
                replyIntent,
                pendingIntentFlags(true));
    }

    private static PendingIntent getStatusComposeIntent(Context context, Notification body, AccountEntity account) {
        Status status = body.getStatus();

        String citedLocalAuthor = status.getAccount().getLocalUsername();
        String citedText = parseAsMastodonHtml(status.getContent()).toString();
        String inReplyToId = status.getId();
        Status actionableStatus = status.getActionableStatus();
        Status.Visibility replyVisibility = actionableStatus.getVisibility();
        String contentWarning = actionableStatus.getSpoilerText();
        List<Status.Mention> mentions = actionableStatus.getMentions();
        Set<String> mentionedUsernames = new LinkedHashSet<>();
        mentionedUsernames.add(actionableStatus.getAccount().getUsername());
        for (Status.Mention mention : mentions) {
            String mentionedUsername = mention.getUsername();
            if (!mentionedUsername.equals(account.getUsername())) {
                mentionedUsernames.add(mention.getUsername());
            }
        }

        ComposeActivity.ComposeOptions composeOptions = new ComposeActivity.ComposeOptions();
        composeOptions.setInReplyToId(inReplyToId);
        composeOptions.setReplyVisibility(replyVisibility);
        composeOptions.setContentWarning(contentWarning);
        composeOptions.setReplyingStatusAuthor(citedLocalAuthor);
        composeOptions.setReplyingStatusContent(citedText);
        composeOptions.setMentionedUsernames(mentionedUsernames);
        composeOptions.setModifiedInitialState(true);
        composeOptions.setLanguage(actionableStatus.getLanguage());
        composeOptions.setKind(ComposeActivity.ComposeKind.NEW);

        Intent composeIntent = MainActivity.composeIntent(context, composeOptions, account.getId(), body.getId(), (int)account.getId());

        // make sure a new instance of MainActivity is started and old ones get destroyed
        composeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        return PendingIntent.getActivity(context.getApplicationContext(),
                notificationId,
                composeIntent,
                pendingIntentFlags(false));
    }

    /**
     * Creates a notification channel for notifications for background work that should not
     * disturb the user.
     *
     * @param context context
     */
    public static void createWorkerNotificationChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel channel = new NotificationChannel(
            CHANNEL_BACKGROUND_TASKS,
            context.getString(R.string.notification_listenable_worker_name),
            NotificationManager.IMPORTANCE_NONE
        );

        channel.setDescription(context.getString(R.string.notification_listenable_worker_description));
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setShowBadge(false);

        notificationManager.createNotificationChannel(channel);
    }

    /**
     * Creates a notification for a background worker.
     *
     * @param context context
     * @param titleResource String resource to use as the notification's title
     * @return the notification
     */
    @NonNull
    public static android.app.Notification createWorkerNotification(@NonNull Context context, @StringRes int titleResource) {
        String title = context.getString(titleResource);
        return new NotificationCompat.Builder(context, CHANNEL_BACKGROUND_TASKS)
            .setContentTitle(title)
            .setTicker(title)
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .build();
    }

    public static void createNotificationChannelsForAccount(@NonNull AccountEntity account, @NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            String[] channelIds = new String[]{
                    CHANNEL_MENTION + account.getIdentifier(),
                    CHANNEL_FOLLOW + account.getIdentifier(),
                    CHANNEL_FOLLOW_REQUEST + account.getIdentifier(),
                    CHANNEL_BOOST + account.getIdentifier(),
                    CHANNEL_FAVOURITE + account.getIdentifier(),
                    CHANNEL_POLL + account.getIdentifier(),
                    CHANNEL_SUBSCRIPTIONS + account.getIdentifier(),
                    CHANNEL_SIGN_UP + account.getIdentifier(),
                    CHANNEL_UPDATES + account.getIdentifier(),
                    CHANNEL_REPORT + account.getIdentifier(),
            };
            int[] channelNames = {
                    R.string.notification_mention_name,
                    R.string.notification_follow_name,
                    R.string.notification_follow_request_name,
                    R.string.notification_boost_name,
                    R.string.notification_favourite_name,
                    R.string.notification_poll_name,
                    R.string.notification_subscription_name,
                    R.string.notification_sign_up_name,
                    R.string.notification_update_name,
                    R.string.notification_report_name,
            };
            int[] channelDescriptions = {
                    R.string.notification_mention_descriptions,
                    R.string.notification_follow_description,
                    R.string.notification_follow_request_description,
                    R.string.notification_boost_description,
                    R.string.notification_favourite_description,
                    R.string.notification_poll_description,
                    R.string.notification_subscription_description,
                    R.string.notification_sign_up_description,
                    R.string.notification_update_description,
                    R.string.notification_report_description,
            };

            List<NotificationChannel> channels = new ArrayList<>(6);

            NotificationChannelGroup channelGroup = new NotificationChannelGroup(account.getIdentifier(), account.getFullName());

            notificationManager.createNotificationChannelGroup(channelGroup);

            for (int i = 0; i < channelIds.length; i++) {
                String id = channelIds[i];
                String name = context.getString(channelNames[i]);
                String description = context.getString(channelDescriptions[i]);
                int importance = NotificationManager.IMPORTANCE_DEFAULT;
                NotificationChannel channel = new NotificationChannel(id, name, importance);

                channel.setDescription(description);
                channel.enableLights(true);
                channel.setLightColor(0xFF2B90D9);
                channel.enableVibration(true);
                channel.setShowBadge(true);
                channel.setGroup(account.getIdentifier());
                channels.add(channel);
            }

            notificationManager.createNotificationChannels(channels);
        }
    }

    public static void deleteNotificationChannelsForAccount(@NonNull AccountEntity account, @NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            notificationManager.deleteNotificationChannelGroup(account.getIdentifier());
        }
    }

    public static boolean areNotificationsEnabled(@NonNull Context context, @NonNull AccountManager accountManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            // on Android >= O, notifications are enabled, if at least one channel is enabled
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (notificationManager.areNotificationsEnabled()) {
                for (NotificationChannel channel : notificationManager.getNotificationChannels()) {
                    if (channel != null && channel.getImportance() > NotificationManager.IMPORTANCE_NONE) {
                        Log.d(TAG, "NotificationsEnabled");
                        return true;
                    }
                }
            }
            Log.d(TAG, "NotificationsDisabled");

            return false;

        } else {
            // on Android < O, notifications are enabled, if at least one account has notification enabled
            return accountManager.areNotificationsEnabled();
        }

    }

    public static void enablePullNotifications(@NonNull Context context) {
        WorkManager workManager = WorkManager.getInstance(context);
        workManager.cancelAllWorkByTag(NOTIFICATION_PULL_TAG);

        // Periodic work requests are supposed to start running soon after being enqueued. In
        // practice that may not be soon enough, so create and enqueue an expedited one-time
        // request to get new notifications immediately.
        WorkRequest fetchNotifications = new OneTimeWorkRequest.Builder(NotificationWorker.class)
            .setExpedited(OutOfQuotaPolicy.DROP_WORK_REQUEST)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();
        workManager.enqueue(fetchNotifications);

        WorkRequest workRequest = new PeriodicWorkRequest.Builder(
                NotificationWorker.class,
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS,
                PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS, TimeUnit.MILLISECONDS
        )
                .addTag(NOTIFICATION_PULL_TAG)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build();

        workManager.enqueue(workRequest);

        Log.d(TAG, "enabled notification checks with "+ PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS + "ms interval");
    }

    public static void disablePullNotifications(@NonNull Context context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(NOTIFICATION_PULL_TAG);
        Log.d(TAG, "disabled notification checks");
    }

    public static void clearNotificationsForAccount(@NonNull Context context, @NonNull AccountEntity account) {
        int accountId = (int) account.getId();

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification androidNotification : notificationManager.getActiveNotifications()) {
            if (accountId == androidNotification.getId()) {
                notificationManager.cancel(androidNotification.getTag(), androidNotification.getId());
            }
        }
    }

    public static boolean filterNotification(@NonNull NotificationManager notificationManager, @NonNull AccountEntity account, @NonNull Notification notification) {
        return filterNotification(notificationManager, account, notification.getType());
    }

    public static boolean filterNotification(@NonNull NotificationManager notificationManager, @NonNull AccountEntity account, @NonNull Notification.Type type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = getChannelId(account, type);
            if(channelId == null) {
                // unknown notificationtype
                return false;
            }
            NotificationChannel channel = notificationManager.getNotificationChannel(channelId);
            return channel != null && channel.getImportance() > NotificationManager.IMPORTANCE_NONE;
        }

        switch (type) {
            case MENTION:
                return account.getNotificationsMentioned();
            case STATUS:
                return account.getNotificationsSubscriptions();
            case FOLLOW:
                return account.getNotificationsFollowed();
            case FOLLOW_REQUEST:
                return account.getNotificationsFollowRequested();
            case REBLOG:
                return account.getNotificationsReblogged();
            case FAVOURITE:
                return account.getNotificationsFavorited();
            case POLL:
                return account.getNotificationsPolls();
            case SIGN_UP:
                return account.getNotificationsSignUps();
            case UPDATE:
                return account.getNotificationsUpdates();
            case REPORT:
                return account.getNotificationsReports();
            default:
                return false;
        }
    }

    @Nullable
    private static String getChannelId(AccountEntity account, Notification notification) {
        return getChannelId(account, notification.getType());
    }

    @Nullable
    private static String getChannelId(AccountEntity account, Notification.Type type) {
        switch (type) {
            case MENTION:
                return CHANNEL_MENTION + account.getIdentifier();
            case STATUS:
                return CHANNEL_SUBSCRIPTIONS + account.getIdentifier();
            case FOLLOW:
                return CHANNEL_FOLLOW + account.getIdentifier();
            case FOLLOW_REQUEST:
                return CHANNEL_FOLLOW_REQUEST + account.getIdentifier();
            case REBLOG:
                return CHANNEL_BOOST + account.getIdentifier();
            case FAVOURITE:
                return CHANNEL_FAVOURITE + account.getIdentifier();
            case POLL:
                return CHANNEL_POLL + account.getIdentifier();
            case SIGN_UP:
                return CHANNEL_SIGN_UP + account.getIdentifier();
            case UPDATE:
                return CHANNEL_UPDATES + account.getIdentifier();
            case REPORT:
                return CHANNEL_REPORT + account.getIdentifier();
            default:
                return null;
        }

    }

    private static void setSoundVibrationLight(AccountEntity account, NotificationCompat.Builder builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return;  //do nothing on Android O or newer, the system uses the channel settings anyway
        }

        if (account.getNotificationSound()) {
            builder.setSound(Settings.System.DEFAULT_NOTIFICATION_URI);
        }

        if (account.getNotificationVibration()) {
            builder.setVibrate(new long[]{500, 500});
        }

        if (account.getNotificationLight()) {
            builder.setLights(0xFF2B90D9, 300, 1000);
        }
    }

    private static String joinNames(Context context, List<StatusBarNotification> notifications1, List<Notification> notifications2) {
        List<String> names = new ArrayList<>(notifications1.size() + notifications2.size());

        for (StatusBarNotification notification: notifications1) {
            names.add(notification.getNotification().extras.getString(EXTRA_ACCOUNT_NAME));
        }

        for (Notification notification : notifications2) {
            names.add(notification.getAccount().getName());
        }

        return joinNames(context, names);
    }

    @Nullable
    private static String joinNames(Context context, List<String> names) {
        if (names.size() > 3) {
            int length = names.size();
            return context.getString(R.string.notification_summary_large,
                StringUtils.unicodeWrap(names.get(length - 1)),
                StringUtils.unicodeWrap(names.get(length - 2)),
                StringUtils.unicodeWrap(names.get(length - 3)),
                length - 3
            );
        } else if (names.size() == 3) {
            return context.getString(R.string.notification_summary_medium,
                StringUtils.unicodeWrap(names.get(2)),
                StringUtils.unicodeWrap(names.get(1)),
                StringUtils.unicodeWrap(names.get(0))
            );
        } else if (names.size() == 2) {
            return context.getString(R.string.notification_summary_small,
                StringUtils.unicodeWrap(names.get(1)),
                StringUtils.unicodeWrap(names.get(0))
            );
        }

        return null;
    }

    @Nullable
    private static String titleForType(Context context, Notification notification, AccountEntity account) {
        String accountName = StringUtils.unicodeWrap(notification.getAccount().getName());
        switch (notification.getType()) {
            case MENTION:
                return context.getString(R.string.notification_mention_format, accountName);
            case STATUS:
                return context.getString(R.string.notification_subscription_format, accountName);
            case FOLLOW:
                return context.getString(R.string.notification_follow_format, accountName);
            case FOLLOW_REQUEST:
                return context.getString(R.string.notification_follow_request_format, accountName);
            case FAVOURITE:
                return context.getString(R.string.notification_favourite_format, accountName);
            case REBLOG:
                return context.getString(R.string.notification_reblog_format, accountName);
            case POLL:
                if(notification.getStatus().getAccount().getId().equals(account.getAccountId())) {
                    return context.getString(R.string.poll_ended_created);
                } else {
                    return context.getString(R.string.poll_ended_voted);
                }
            case SIGN_UP:
                return context.getString(R.string.notification_sign_up_format, accountName);
            case UPDATE:
                return context.getString(R.string.notification_update_format, accountName);
            case REPORT:
                return context.getString(R.string.notification_report_format, account.getDomain());
        }
        return null;
    }

    private static String bodyForType(Notification notification, Context context, Boolean alwaysOpenSpoiler) {
        switch (notification.getType()) {
            case FOLLOW:
            case FOLLOW_REQUEST:
            case SIGN_UP:
                return "@" + notification.getAccount().getUsername();
            case MENTION:
            case FAVOURITE:
            case REBLOG:
            case STATUS:
                if (!TextUtils.isEmpty(notification.getStatus().getSpoilerText()) && !alwaysOpenSpoiler) {
                    return notification.getStatus().getSpoilerText();
                } else {
                    return parseAsMastodonHtml(notification.getStatus().getContent()).toString();
                }
            case POLL:
                if (!TextUtils.isEmpty(notification.getStatus().getSpoilerText()) && !alwaysOpenSpoiler) {
                    return notification.getStatus().getSpoilerText();
                } else {
                    StringBuilder builder = new StringBuilder(parseAsMastodonHtml(notification.getStatus().getContent()));
                    builder.append('\n');
                    Poll poll = notification.getStatus().getPoll();
                    List<PollOption> options = poll.getOptions();
                    for(int i = 0; i < options.size(); ++i) {
                        PollOption option = options.get(i);
                        builder.append(buildDescription(option.getTitle(),
                                PollViewDataKt.calculatePercent(option.getVotesCount(), poll.getVotersCount(), poll.getVotesCount()),
                                poll.getOwnVotes().contains(i),
                                context));
                        builder.append('\n');
                    }
                    return builder.toString();
                }
            case REPORT:
                return context.getString(
                        R.string.notification_header_report_format,
                        StringUtils.unicodeWrap(notification.getAccount().getName()),
                        StringUtils.unicodeWrap(notification.getReport().getTargetAccount().getName())
                );
        }
        return null;
    }

    public static int pendingIntentFlags(boolean mutable) {
        if (mutable) {
            return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        } else {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
    }
}
