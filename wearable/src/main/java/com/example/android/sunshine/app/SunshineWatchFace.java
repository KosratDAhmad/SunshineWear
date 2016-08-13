/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String TAG = "SunshineWatchFace";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a minute.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private static final String TAG = "WatchFace.Engine";
        private GoogleApiClient mGoogleApiClient;

        private static final String REQ_PATH = "/weather";
        private static final String REQ_WEATHER_PATH = "/weather-req";
        private static final String KEY_WEATHER_ID = "com.example.key.weather_id";
        private static final String KEY_TEMP_MAX = "com.example.key.max_temp";
        private static final String KEY_TEMP_MIN = "com.example.key.min_temp";

        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private boolean mRegisteredTimeZoneReceiver = false;
        private Paint mBackgroundPaint;
        private Paint mHourPaint;
        private Paint mMinutePaint;
        private Paint mDatePaint;
        private Paint mColonPaint;
        private Paint mAmPmPaint;
        private Paint mDividerPaint;
        private Paint mWeatherIconPaint;
        private Paint mLowTempPaint;
        private Paint mHighTempPaint;

        private int mWeatherId = 0;
        private double mMaxTemperature = 11;
        private double mMinTemperature = 22;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        private float mYOffset;
        private float mLineHeight;
        private String mAmString;
        private String mPmString;
        private Bitmap weatherIcon;
        private Calendar mCalendar;
        private Date mDate;
        private SimpleDateFormat mDayOfWeekFormat;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            int timeColor = resources.getColor(R.color.digital_time_color);

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mDatePaint = createTextPaint(resources.getColor(R.color.digital_date_color));

            mHourPaint = createTextPaint(timeColor, BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(timeColor);

            mColonPaint = createTextPaint(timeColor);

            mAmPmPaint = createTextPaint(resources.getColor(R.color.digital_am_pm_color));

            // initialize horizontal divider paints
            mDividerPaint = new Paint();
            mDividerPaint.setColor(resources.getColor(R.color.digital_divider_color));

            // initialize weather info
            mWeatherIconPaint = new Paint();
            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_high_temp_color));
            mLowTempPaint = createTextPaint(resources.getColor(R.color.digital_low_temp_color));


            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                if (null == mGoogleApiClient) {
                    mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                            .addConnectionCallbacks(this)
                            .addOnConnectionFailedListener(this)
                            .addApi(Wearable.API)
                            .build();
                }

                if (!mGoogleApiClient.isConnected())
                    mGoogleApiClient.connect();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void requestWeatherUpdate() {
            Log.d(TAG, "requestWeatherUpdate through Message API");

            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient)
                    .setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                        @Override
                        public void onResult(NodeApi.GetConnectedNodesResult getConnectedNodesResult) {
                            final List<Node> nodes = getConnectedNodesResult.getNodes();

                            for (Node node : nodes) {
                                Wearable.MessageApi.sendMessage(mGoogleApiClient
                                        , node.getId()
                                        , REQ_WEATHER_PATH
                                        , new byte[0]).setResultCallback(
                                        new ResultCallback<MessageApi.SendMessageResult>() {
                                            @Override
                                            public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                                                if (sendMessageResult.getStatus().isSuccess()) {
                                                    Log.d(TAG, "Message successfully sent");
                                                } else {
                                                    Log.d(TAG, "Message failed to send");
                                                }
                                            }
                                        }
                                );
                            }
                        }
                    });
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float amPmSize = resources.getDimension(isRound
                    ? R.dimen.digital_am_pm_size_round : R.dimen.digital_am_pm_size);

            float tempSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_size_round : R.dimen.digital_temp_size);

            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mAmPmPaint.setTextSize(amPmSize);
            mHighTempPaint.setTextSize(tempSize);
            mLowTempPaint.setTextSize(tempSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            mAmPmPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_am_pm_color_ambient) :
                    getResources().getColor(R.color.digital_am_pm_color));

            mDatePaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_date_color_ambient) :
                    getResources().getColor(R.color.digital_date_color));

            mDividerPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_divider_color_ambient) :
                    getResources().getColor(R.color.digital_divider_color));

            mHighTempPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_high_temp_color_ambient) :
                    getResources().getColor(R.color.digital_high_temp_color));

            mLowTempPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_low_temp_color_ambient) :
                    getResources().getColor(R.color.digital_low_temp_color));

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mDatePaint.setAntiAlias(antiAlias);
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
                mDividerPaint.setAntiAlias(antiAlias);
                mWeatherIconPaint.setAntiAlias(antiAlias);
                mHighTempPaint.setAntiAlias(antiAlias);
                mLowTempPaint.setAntiAlias(antiAlias);
            }
            invalidate();


            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }

        private Bitmap getBitmapForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://openweathermap.org/weather-conditions
            int weatherIconId = R.drawable.ic_clear;
            if (weatherId >= 200 && weatherId <= 232) {
                weatherIconId = R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                weatherIconId = R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                weatherIconId = R.drawable.ic_rain;
            } else if (weatherId == 511) {
                weatherIconId = R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                weatherIconId = R.drawable.ic_light_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                weatherIconId = R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                weatherIconId = R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                weatherIconId = R.drawable.ic_storm;
            } else if (weatherId == 800) {
                weatherIconId = R.drawable.ic_clear;
            } else if (weatherId == 801) {
                weatherIconId = R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                weatherIconId = R.drawable.ic_cloudy;
            }

            // default bitmap
            return BitmapFactory.decodeResource(getResources(), weatherIconId);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFace.this);

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            int centerAdjust = 10;
            String colonString = ":";

            // Draw the hours.
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            canvas.drawText(hourString,
                    bounds.centerX() - (mHourPaint.measureText(hourString) + centerAdjust),
                    mYOffset,
                    mHourPaint);


            // Draw Colon
            canvas.drawText(colonString,
                    bounds.centerX() - centerAdjust,
                    mYOffset,
                    mColonPaint);

            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString,
                    bounds.centerX() + mColonPaint.measureText(colonString) - centerAdjust,
                    mYOffset,
                    mMinutePaint);

            // In unmuted interactive mode, draw a second blinking colon followed by the seconds.
            // Otherwise, if we're in 12-hour mode, draw AM/PM
            if (!is24Hour) {
                canvas.drawText(getAmPmString(mCalendar.get(Calendar.AM_PM)),
                        bounds.centerX() + mMinutePaint.measureText(minuteString) + centerAdjust,
                        mYOffset,
                        mAmPmPaint);
            }


            String formattedDate = mDayOfWeekFormat.format(mDate).toUpperCase();
            // Day of week
            canvas.drawText(formattedDate,
                    bounds.centerX() - (mDatePaint.measureText(formattedDate)) / 2,
                    mYOffset + mLineHeight,
                    mDatePaint);

            // draw a horizontal divider
            int lineWidth = 70;
            canvas.drawLine(bounds.centerX() - lineWidth / 2,
                    mYOffset + (mLineHeight * 1.8f),
                    bounds.centerX() + lineWidth / 2,
                    mYOffset + (mLineHeight * 1.8f),
                    mDividerPaint);

            // draw weather icon
            weatherIcon = getBitmapForWeatherCondition(mWeatherId);
            float xImage = (bounds.width() / 6 + (bounds.width() / 6 - weatherIcon.getHeight()) / 2);
            if (isInAmbientMode()) {
                ColorMatrix colorMatrix = new ColorMatrix();
                colorMatrix.setSaturation(0);
                ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
                mWeatherIconPaint.setColorFilter(filter);
                canvas.drawBitmap(weatherIcon, xImage,
                        mYOffset + (mLineHeight * 2f),
                        mWeatherIconPaint);
            } else {
                mWeatherIconPaint.setColorFilter(null);
                canvas.drawBitmap(weatherIcon,
                        xImage,
                        mYOffset + (mLineHeight * 2f),
                        mWeatherIconPaint);
            }
            String highTempText = String.format(getString(R.string.format_temperature), mMaxTemperature);
            String lowTempText = String.format(getString(R.string.format_temperature), mMinTemperature);
            canvas.drawText(highTempText,
                    bounds.centerX() - 30,
                    mYOffset + (mLineHeight * 3.2f),
                    mHighTempPaint);

            canvas.drawText(lowTempText,
                    bounds.centerX() + 35,
                    mYOffset + (mLineHeight * 3.2f),
                    mLowTempPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            requestWeatherUpdate();
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(REQ_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

                        mWeatherId = dataMap.getInt(KEY_WEATHER_ID);
                        mMaxTemperature = dataMap.getDouble(KEY_TEMP_MAX);
                        mMinTemperature = dataMap.getDouble(KEY_TEMP_MIN);

                        invalidate();
                    }
                }
            }
            dataEventBuffer.release();
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }
    }
}