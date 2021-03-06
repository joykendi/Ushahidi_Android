/** 
 ** Copyright (c) 2010 Ushahidi Inc
 ** All rights reserved
 ** Contact: team@ushahidi.com
 ** Website: http://www.ushahidi.com
 ** 
 ** GNU Lesser General Public License Usage
 ** This file may be used under the terms of the GNU Lesser
 ** General Public License version 3 as published by the Free Software
 ** Foundation and appearing in the file LICENSE.LGPL included in the
 ** packaging of this file. Please review the following information to
 ** ensure the GNU Lesser General Public License version 3 requirements
 ** will be met: http://www.gnu.org/licenses/lgpl.html.	
 **	
 **
 ** If you have questions regarding the use of this file, please contact
 ** Ushahidi developers at team@ushahidi.com.
 ** 
 **/

package com.ushahidi.android.app;

import java.io.IOException;
import java.util.HashMap;

import com.ushahidi.android.app.net.UshahidiHttpClient;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.telephony.SmsMessage;

public class SmsReceiverService extends Service {
    private static final String ACTION_SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

    private ServiceHandler mServiceHandler;

    private Looper mServiceLooper;

    private String fromAddress = "";

    private String messageBody = "";

    private static final Object mStartingServiceSync = new Object();

    private static final String TAG = "Ushahidi";

    private static PowerManager.WakeLock mStartingService;

    private HashMap<String, String> params = new HashMap<String, String>();

    private Context mContext;

    @Override
    public void onCreate() {

        HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mContext = getApplicationContext();
        UshahidiService.loadSettings(mContext);
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);
    }

    @Override
    public void onStart(Intent intent, int startId) {

        // int mResultCode = intent != null ? intent.getIntExtra("result", 0) :
        // 0;
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        msg.obj = intent;
        mServiceHandler.sendMessage(msg);
    }

    @Override
    public void onDestroy() {

        mServiceLooper.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {

            int serviceId = msg.arg1;
            Intent intent = (Intent)msg.obj;
            String action = intent.getAction();
            // String dataType = intent.getType();

            if (ACTION_SMS_RECEIVED.equals(action)) {
                handleSmsReceived(intent);
            }

            finishStartingService(SmsReceiverService.this, serviceId);
        }
    }

    /**
     * Handle receiving a SMS message
     */
    private void handleSmsReceived(Intent intent) {

        Bundle bundle = intent.getExtras();
        UshahidiService.loadSettings(mContext);

        if (bundle != null) {
            SmsMessage[] messages = getMessagesFromIntent(intent);
            SmsMessage sms = messages[0];
            if (messages != null) {
                // extract message details phone number and the message body
                fromAddress = sms.getDisplayOriginatingAddress();

                String body;
                if (messages.length == 1 || sms.isReplace()) {
                    body = sms.getDisplayMessageBody();
                } else {
                    StringBuilder bodyText = new StringBuilder();
                    for (int i = 0; i < messages.length; i++) {
                        bodyText.append(messages[i].getMessageBody());
                    }
                    body = bodyText.toString();
                }
                messageBody = body;
            }
        }

        // post sms message to ushahidi
        if (UshahidiService.smsUpdate) {
            if (Util.isConnected(SmsReceiverService.this)) {
                if (!this.postToUshahidi()) {
                    this.showNotification(messageBody, R.string.sms_failed);
                } else {
                    this.showNotification(messageBody, R.string.sms_sent);
                }
            }
        }
    }

    private boolean postToUshahidi() {
        UshahidiService.loadSettings(mContext);
        StringBuilder urlBuilder = new StringBuilder(UshahidiService.domain);

        urlBuilder.append("/api");
        params.put("task", "sms");
        params.put("username", UshahidiService.username);
        params.put("password", UshahidiService.password);
        params.put("message_from", fromAddress);
        params.put("message_description", messageBody);

        try {
            return UshahidiHttpClient.postSmsToUshahidi(urlBuilder.toString(), params);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static final SmsMessage[] getMessagesFromIntent(Intent intent) {
        Object[] messages = (Object[])intent.getSerializableExtra("pdus");
        if (messages == null) {
            return null;
        }
        if (messages.length == 0) {
            return null;
        }

        byte[][] pduObjs = new byte[messages.length][];

        for (int i = 0; i < messages.length; i++) {
            pduObjs[i] = (byte[])messages[i];
        }
        byte[][] pdus = new byte[pduObjs.length][];
        int pduCount = pdus.length;
        SmsMessage[] msgs = new SmsMessage[pduCount];
        for (int i = 0; i < pduCount; i++) {
            pdus[i] = pduObjs[i];
            msgs[i] = SmsMessage.createFromPdu(pdus[i]);
        }
        return msgs;
    }

    /**
     * Start the service to process the current event notifications, acquiring
     * the wake lock before returning to ensure that the service will run.
     */
    public static void beginStartingService(Context context, Intent intent) {
        synchronized (mStartingServiceSync) {

            if (mStartingService == null) {
                PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
                mStartingService = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "Sms messages .SmsReceiverService");
                mStartingService.setReferenceCounted(false);
            }

            mStartingService.acquire();
            context.startService(intent);
        }
    }

    /**
     * Called back by the service when it has finished processing notifications,
     * releasing the wake lock if the service is now stopping.
     */
    public static void finishStartingService(Service service, int startId) {
        synchronized (mStartingServiceSync) {

            if (mStartingService != null) {
                if (service.stopSelfResult(startId)) {
                    mStartingService.release();
                }
            }
        }
    }

    /**
     * Show a notification
     * 
     * @param String message to display
     * @param String notification title
     */
    private void showNotification(String message, int notification_title) {

        Intent baseIntent = new Intent(this, Settings.class);
        baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

        Notification notification = new Notification(R.drawable.favicon,
                getString(R.string.sms_status), System.currentTimeMillis());
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, baseIntent, 0);
        notification
                .setLatestEventInfo(this, getString(notification_title), message, pendingIntent);
        notificationManager.notify(1, notification);

    }
}
