package com.audacious_software.passive_data_kit.generators.sensors;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.activities.generators.DataPointViewHolder;
import com.audacious_software.passive_data_kit.diagnostics.DiagnosticAction;
import com.audacious_software.passive_data_kit.generators.Generator;
import com.audacious_software.passive_data_kit.generators.Generators;
import com.audacious_software.pdk.passivedatakit.R;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by cjkarr on 4/17/2017.
 */

public class Accelerometer extends SensorGenerator implements SensorEventListener {
    private static final String GENERATOR_IDENTIFIER = "pdk-sensor-accelerometer";

    private static final String ENABLED = "com.audacious_software.passive_data_kit.generators.device.Accelerometer.ENABLED";
    private static final boolean ENABLED_DEFAULT = true;

    private static final String IGNORE_POWER_MANAGEMENT = "com.audacious_software.passive_data_kit.generators.sensors.Accelerometer.IGNORE_POWER_MANAGEMENT";
    private static final boolean IGNORE_POWER_MANAGEMENT_DEFAULT = true;

    private static final String REFRESH_INTERVAL = "com.audacious_software.passive_data_kit.generators.sensors.Accelerometer.REFRESH_INTERVAL";
    private static final long REFRESH_INTERVAL_DEFAULT = 0;

    private static final String REFRESH_DURATION = "com.audacious_software.passive_data_kit.generators.sensors.Accelerometer.REFRESH_DURATION";
    private static final long REFRESH_DURATION_DEFAULT = (5 * 60 * 1000);

    private static final String DATABASE_PATH = "pdk-sensor-accelerometer.sqlite";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_HISTORY = "history";

    public static final String HISTORY_OBSERVED = "observed";
    private static final String HISTORY_RAW_TIMESTAMP = "raw_timestamp";
    private static final String HISTORY_ACCURACY = "accuracy";
    public static final String HISTORY_X = "x";
    public static final String HISTORY_Y = "y";
    public static final String HISTORY_Z = "z";

    private static Accelerometer sInstance = null;
    private static Handler sHandler = null;

    private static boolean sIsDrawing = false;
    private static long sLastDrawStart = 0;

    private SQLiteDatabase mDatabase = null;

    private Sensor mSensor = null;

    private static int NUM_BUFFERS = 3;
    private static int BUFFER_SIZE = 1024;

    private long mLastCleanup = 0;
    private long mCleanupInterval = 15 * 60 * 1000;

    private int mActiveBuffersIndex = 0;
    private int mCurrentBufferIndex = 0;

    private float[][] mXValueBuffers = null;
    private float[][] mYValueBuffers = null;
    private float[][] mZValueBuffers = null;
    private int[][] mAccuracyBuffers = null;
    private long[][] mRawTimestampBuffers = null;
    private long[][] mTimestampBuffers = null;

    long mBaseTimestamp = 0;

    private long mLatestTimestamp = 0;
    private Thread mIntervalThread = null;

    public static Accelerometer getInstance(Context context) {
        if (Accelerometer.sInstance == null) {
            Accelerometer.sInstance = new Accelerometer(context.getApplicationContext());
        }

        return Accelerometer.sInstance;
    }

    public Accelerometer(Context context) {
        super(context);
    }

    public static void start(final Context context) {
        Accelerometer.getInstance(context).startGenerator();
    }

    public void setIgnorePowerManagement(boolean ignore) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.mContext);
        SharedPreferences.Editor e = prefs.edit();

        e.putBoolean(Accelerometer.IGNORE_POWER_MANAGEMENT, ignore);
        e.apply();

        this.stopGenerator();
        this.startGenerator();
    }

    public void setRefreshInterval(long interval) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.mContext);
        SharedPreferences.Editor e = prefs.edit();

        e.putLong(Accelerometer.REFRESH_INTERVAL, interval);
        e.apply();

        this.stopGenerator();
        this.startGenerator();
    }

    public void setRefreshDuration(long duration) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.mContext);
        SharedPreferences.Editor e = prefs.edit();

        e.putLong(Accelerometer.REFRESH_DURATION, duration);
        e.apply();

        this.stopGenerator();
        this.startGenerator();
    }

    private void startGenerator() {
        final SensorManager sensors = (SensorManager) this.mContext.getSystemService(Context.SENSOR_SERVICE);

        final Accelerometer me = this;

        Generators.getInstance(this.mContext).registerCustomViewClass(Accelerometer.GENERATOR_IDENTIFIER, Accelerometer.class);

        File path = PassiveDataKit.getGeneratorsStorage(this.mContext);

        path = new File(path, Accelerometer.DATABASE_PATH);

        this.mDatabase = SQLiteDatabase.openOrCreateDatabase(path, null);

        int version = this.getDatabaseVersion(this.mDatabase);

        switch (version) {
            case 0:
                this.mDatabase.execSQL(this.mContext.getString(R.string.pdk_generator_accelerometer_create_history_table));
        }

        this.setDatabaseVersion(this.mDatabase, Accelerometer.DATABASE_VERSION);

        if (Accelerometer.isEnabled(this.mContext)) {
            this.mSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

            Runnable r = new Runnable()
            {
                public void run()
                {
                    Looper.prepare();

                    me.mXValueBuffers = new float[Accelerometer.NUM_BUFFERS][Accelerometer.BUFFER_SIZE];
                    me.mYValueBuffers = new float[Accelerometer.NUM_BUFFERS][Accelerometer.BUFFER_SIZE];
                    me.mZValueBuffers = new float[Accelerometer.NUM_BUFFERS][Accelerometer.BUFFER_SIZE];
                    me.mAccuracyBuffers = new int[Accelerometer.NUM_BUFFERS][Accelerometer.BUFFER_SIZE];
                    me.mRawTimestampBuffers = new long[Accelerometer.NUM_BUFFERS][Accelerometer.BUFFER_SIZE];
                    me.mTimestampBuffers = new long[Accelerometer.NUM_BUFFERS][Accelerometer.BUFFER_SIZE];

                    me.mActiveBuffersIndex = 0;
                    me.mCurrentBufferIndex = 0;

                    Accelerometer.sHandler = new Handler();

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                        sensors.registerListener(me, me.mSensor, SensorManager.SENSOR_DELAY_NORMAL, 0, Accelerometer.sHandler);
                    else
                        sensors.registerListener(me, me.mSensor, SensorManager.SENSOR_DELAY_NORMAL, Accelerometer.sHandler);

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me.mContext);

                    final long refreshInterval = prefs.getLong(Accelerometer.REFRESH_INTERVAL, Accelerometer.REFRESH_INTERVAL_DEFAULT);

                    if (refreshInterval > 0) {
                        final long refreshDuration = prefs.getLong(Accelerometer.REFRESH_DURATION, Accelerometer.REFRESH_DURATION_DEFAULT);

                        if (me.mIntervalThread != null) {
                            me.mIntervalThread.interrupt();
                            me.mIntervalThread = null;
                        }

                        Runnable managerRunnable = new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    while (true) {
                                        Thread.sleep(refreshDuration);

                                        sensors.unregisterListener(me, me.mSensor);

                                        Thread.sleep(refreshInterval - refreshDuration);

                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                                            sensors.registerListener(me, me.mSensor, SensorManager.SENSOR_DELAY_NORMAL, 0, Accelerometer.sHandler);
                                        else
                                            sensors.registerListener(me, me.mSensor, SensorManager.SENSOR_DELAY_NORMAL, Accelerometer.sHandler);
                                    }
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        };

                        me.mIntervalThread = new Thread(managerRunnable, "accelerometer-interval");
                        me.mIntervalThread.start();
                    }

                    Looper.loop();
                }
            };

            Thread t = new Thread(r, "accelerometer");
            t.start();

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me.mContext);

            if (prefs.getBoolean(Accelerometer.IGNORE_POWER_MANAGEMENT, Accelerometer.IGNORE_POWER_MANAGEMENT_DEFAULT)) {
                Generators.getInstance(this.mContext).acquireWakeLock(Accelerometer.IDENTIFIER, PowerManager.PARTIAL_WAKE_LOCK);
            } else {
                Generators.getInstance(this.mContext).releaseWakeLock(Accelerometer.IDENTIFIER);
            }
        } else {
            this.stopGenerator();
        }
    }

    private void stopGenerator() {
        final SensorManager sensors = (SensorManager) this.mContext.getSystemService(Context.SENSOR_SERVICE);

        if (this.mSensor != null) {
            if (this.mIntervalThread != null) {
                this.mIntervalThread.interrupt();
                this.mIntervalThread = null;
            }

            sensors.unregisterListener(this, this.mSensor);

            if (Accelerometer.sHandler != null) {
                Looper loop = Accelerometer.sHandler.getLooper();
                loop.quit();

                Accelerometer.sHandler = null;
            }

            this.mXValueBuffers = null;
            this.mYValueBuffers = null;
            this.mZValueBuffers = null;
            this.mAccuracyBuffers = null;
            this.mRawTimestampBuffers = null;
            this.mTimestampBuffers = null;

            this.mActiveBuffersIndex = 0;
            this.mCurrentBufferIndex = 0;

            this.mSensor = null;
        }

        Generators.getInstance(this.mContext).releaseWakeLock(Accelerometer.IDENTIFIER);
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = Generators.getInstance(context).getSharedPreferences(context);

        return prefs.getBoolean(Accelerometer.ENABLED, Accelerometer.ENABLED_DEFAULT);
    }

    public static boolean isRunning(Context context) {
        if (Accelerometer.sInstance == null) {
            return false;
        }

        return Accelerometer.sInstance.mSensor != null;
    }

    public static ArrayList<DiagnosticAction> diagnostics(final Context context) {
        ArrayList<DiagnosticAction> actions = new ArrayList<>();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (prefs.getBoolean(Accelerometer.IGNORE_POWER_MANAGEMENT, Accelerometer.IGNORE_POWER_MANAGEMENT_DEFAULT)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

                if (power.isIgnoringBatteryOptimizations(context.getPackageName()) == false) {
                    actions.add(new DiagnosticAction(context.getString(R.string.diagnostic_battery_optimization_exempt_title), context.getString(R.string.diagnostic_battery_optimization_exempt), new Runnable() {

                        @Override
                        public void run() {
                            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(intent);
                        }
                    }));
                }
            }
        }

        return actions;
    }

    public static void bindViewHolder(final DataPointViewHolder holder) {
        if (Accelerometer.sIsDrawing) {
            return;
        }

        final long drawStart = System.currentTimeMillis();

        if (drawStart - Accelerometer.sLastDrawStart < (30 * 1000)) {
            return;
        }

        Accelerometer.sLastDrawStart = drawStart;

        Accelerometer.sIsDrawing = true;

        final Context context = holder.itemView.getContext();
        final View itemView = holder.itemView;

        final Accelerometer generator = Accelerometer.getInstance(context);

        final long now = System.currentTimeMillis() / (1000 * 60 * 5);
        final long start = now - (24 * 12); //  * 60);

        View cardContent = itemView.findViewById(R.id.card_content);
        View cardEmpty = itemView.findViewById(R.id.card_empty);
        TextView dateLabel = (TextView) itemView.findViewById(R.id.generator_data_point_date);

        if (context instanceof Activity) {
            cardContent.setVisibility(View.VISIBLE);
            cardEmpty.setVisibility(View.GONE);

            dateLabel.setText(Generator.formatTimestamp(context, Accelerometer.latestPointGenerated(generator.mContext) / 1000));

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    final ArrayList<Entry> xLowValues = new ArrayList<>();
                    final ArrayList<Entry> xHighValues = new ArrayList<>();

                    final ArrayList<Entry> yLowValues = new ArrayList<>();
                    final ArrayList<Entry> yHighValues = new ArrayList<>();

                    final ArrayList<Entry> zLowValues = new ArrayList<>();
                    final ArrayList<Entry> zHighValues = new ArrayList<>();

                    final String where = Accelerometer.HISTORY_OBSERVED + " >= ? AND _id % 1024 = 0";
                    final String[] args = { "" + (System.currentTimeMillis() - (24 * 60 * 60 * 1000)) };

                    Cursor c = generator.mDatabase.query(Accelerometer.TABLE_HISTORY, null, where, args, null, null, Accelerometer.HISTORY_OBSERVED + " DESC");

                    long lastTimestamp = -1;

                    float maxValue = 0;
                    float minValue = 0;

                    float lowX = -1;
                    float highX = -1;

                    float lowY = -1;
                    float highY = -1;

                    float lowZ = -1;
                    float highZ = -1;

                    int whenIndex = c.getColumnIndex(Accelerometer.HISTORY_OBSERVED);
                    int xIndex = c.getColumnIndex(Accelerometer.HISTORY_X);
                    int yIndex = c.getColumnIndex(Accelerometer.HISTORY_Y);
                    int zIndex = c.getColumnIndex(Accelerometer.HISTORY_Z);

                    while (c.moveToNext()) {
                        long when = c.getLong(whenIndex);

                        when = when / (1000 * 1000);
                        when = when / (1000 * 6 * 50);

                        float x = c.getFloat(xIndex);
                        float y = c.getFloat(yIndex);
                        float z = c.getFloat(zIndex);

                        if (lastTimestamp != when) {
                            if (lastTimestamp != -1) {
                                xLowValues.add(0, new Entry(lastTimestamp, lowX));
                                xHighValues.add(0, new Entry(lastTimestamp, highX));

                                yLowValues.add(0, new Entry(lastTimestamp, lowY));
                                yHighValues.add(0, new Entry(lastTimestamp, highY));

                                zLowValues.add(0, new Entry(lastTimestamp, lowZ));
                                zHighValues.add(0, new Entry(lastTimestamp, highZ));
                            }

                            lastTimestamp = when;

                            lowX = x;
                            highX = x;

                            lowY = y;
                            highY = y;

                            lowZ = z;
                            highZ = z;
                        } else {
                            if (x < lowX) {
                                lowX = x;
                            }

                            if (x > highX) {
                                highX = x;
                            }

                            if (y < lowY) {
                                lowY = y;
                            }

                            if (y > highY) {
                                highY = y;
                            }

                            if (z < lowZ) {
                                lowZ = z;
                            }

                            if (z > highZ) {
                                highZ = z;
                            }
                        }

                        if (x > maxValue) {
                            maxValue = x;
                        }

                        if (x < minValue) {
                            minValue = x;
                        }

                        if (y > maxValue) {
                            maxValue = y;
                        }

                        if (y < minValue) {
                            minValue = y;
                        }

                        if (z > maxValue) {
                            maxValue = z;
                        }

                        if (z < minValue) {
                            minValue = z;
                        }
                    }

                    if (lastTimestamp != -1) {
                        xLowValues.add(0, new Entry(lastTimestamp, lowX));
                        xHighValues.add(0, new Entry(lastTimestamp, highX));

                        yLowValues.add(0, new Entry(lastTimestamp, lowY));
                        yHighValues.add(0, new Entry(lastTimestamp, highY));

                        zLowValues.add(0, new Entry(lastTimestamp, lowZ));
                        zHighValues.add(0, new Entry(lastTimestamp, highZ));
                    }

                    c.close();

                    Activity activity = (Activity) context;

                    final float finalMaxValue = maxValue;
                    final float finalMinValue = minValue;

                    final List<ArrayList<Entry>> data = new ArrayList<>();
                    data.add(xLowValues);
                    data.add(xHighValues);
                    data.add(yLowValues);
                    data.add(yHighValues);
                    data.add(zLowValues);
                    data.add(zHighValues);

                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            int[] colors = {
                                    R.color.generator_accelerometer_x_low,
                                    R.color.generator_accelerometer_x_high,
                                    R.color.generator_accelerometer_y_low,
                                    R.color.generator_accelerometer_y_high,
                                    R.color.generator_accelerometer_z_low,
                                    R.color.generator_accelerometer_z_high
                            };

                            LineData chartData = new LineData();

                            for (int i = 0; i < colors.length; i++) {
                                int color = colors[i];

                                ArrayList<Entry> entries = data.get(i);

                                LineDataSet set = new LineDataSet(entries, "");
                                set.setAxisDependency(YAxis.AxisDependency.LEFT);
                                set.setLineWidth(1.0f);
                                set.setDrawCircles(false);
                                set.setFillAlpha(192);
                                set.setDrawFilled(false);
                                set.setDrawValues(true);
                                set.setColor(ContextCompat.getColor(context, color));
                                set.setDrawCircleHole(false);
                                set.setDrawValues(false);
                                set.setMode(LineDataSet.Mode.LINEAR);

                                chartData.addDataSet(set);
                            }

                            final LineChart chart = (LineChart) itemView.findViewById(R.id.accelerometer_chart);

                            if (chart != null) {
                                chart.setViewPortOffsets(0, 0, 0, 0);
                                chart.setHighlightPerDragEnabled(false);
                                chart.setHighlightPerTapEnabled(false);
                                chart.setBackgroundColor(ContextCompat.getColor(context, android.R.color.black));
                                chart.setPinchZoom(false);

                                final DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(context);

                                final XAxis xAxis = chart.getXAxis();
                                xAxis.setPosition(XAxis.XAxisPosition.BOTTOM_INSIDE);
                                xAxis.setTextSize(10f);
                                xAxis.setDrawAxisLine(true);
                                xAxis.setDrawGridLines(true);
                                xAxis.setCenterAxisLabels(true);
                                xAxis.setDrawLabels(true);
                                xAxis.setTextColor(ContextCompat.getColor(context, android.R.color.white));
                                xAxis.setGranularityEnabled(true);
                                xAxis.setGranularity(1);
                                xAxis.setAxisMinimum(start);
                                xAxis.setAxisMaximum(now);
                                xAxis.setValueFormatter(new IAxisValueFormatter() {
                                    @Override
                                    public String getFormattedValue(float value, AxisBase axis) {
                                        Date date = new Date((long) value * 5 * 60 * 1000);

                                        return timeFormat.format(date);
                                    }
                                });

                                YAxis leftAxis = chart.getAxisLeft();
                                leftAxis.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART);
                                leftAxis.setDrawGridLines(true);
                                leftAxis.setDrawAxisLine(true);
                                leftAxis.setGranularityEnabled(true);
                                leftAxis.setTextColor(ContextCompat.getColor(context, android.R.color.white));

                                YAxis rightAxis = chart.getAxisRight();
                                rightAxis.setEnabled(false);

                                chart.getLegend().setEnabled(false);
                                chart.getDescription().setEnabled(false);

                                chart.setVisibleYRange((float) Math.floor(finalMinValue) - 1, (float) Math.ceil(finalMaxValue) + 1, YAxis.AxisDependency.LEFT);
                                chart.setData(chartData);
                            }

                            Accelerometer.sIsDrawing = false;
                        }
                    });
                }
            };

            Thread t = new Thread(r, "render-accelerometer-graph");
            t.start();
        } else {
            cardContent.setVisibility(View.GONE);
            cardEmpty.setVisibility(View.VISIBLE);

            dateLabel.setText(R.string.label_never_pdk);
        }
    }

    public static View fetchView(ViewGroup parent) {
        return LayoutInflater.from(parent.getContext()).inflate(R.layout.card_generator_sensors_accelerometer, parent, false);
    }

    @Override
    public List<Bundle> fetchPayloads() {
        return new ArrayList<>();
    }

    public static long latestPointGenerated(Context context) {
        Accelerometer me = Accelerometer.getInstance(context);

        if (me.mLatestTimestamp == 0) {
            Cursor c = me.mDatabase.query(Accelerometer.TABLE_HISTORY, null, null, null, null, null, Accelerometer.HISTORY_OBSERVED + " DESC", "1");

            if (c.moveToNext()) {
                me.mLatestTimestamp = c.getLong(c.getColumnIndex(Accelerometer.HISTORY_OBSERVED) / 1000);
            }

            c.close();
        }

        return me.mLatestTimestamp;
    }

    public Cursor queryHistory(String[] cols, String where, String[] args, String orderBy) {
        return this.mDatabase.query(Accelerometer.TABLE_HISTORY, cols, where, args, null, null, orderBy);
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        long rawTimestamp = sensorEvent.timestamp;

        if (this.mBaseTimestamp == 0) {
            this.mBaseTimestamp = (System.currentTimeMillis() * (1000 * 1000)) - rawTimestamp;
        }

        int accuracy = sensorEvent.accuracy;
        long normalizedTimestamp = this.mBaseTimestamp + rawTimestamp;

        if (this.mCurrentBufferIndex >= Accelerometer.BUFFER_SIZE) {
            this.saveBuffer(this.mActiveBuffersIndex, this.mCurrentBufferIndex);

            this.mCurrentBufferIndex = 0;
            this.mActiveBuffersIndex += 1;

            if (this.mActiveBuffersIndex >= Accelerometer.NUM_BUFFERS) {
                this.mActiveBuffersIndex = 0;
            }
        }

        this.mXValueBuffers[this.mActiveBuffersIndex][this.mCurrentBufferIndex] = sensorEvent.values[0];
        this.mYValueBuffers[this.mActiveBuffersIndex][this.mCurrentBufferIndex] = sensorEvent.values[1];
        this.mZValueBuffers[this.mActiveBuffersIndex][this.mCurrentBufferIndex] = sensorEvent.values[2];
        this.mAccuracyBuffers[this.mActiveBuffersIndex][this.mCurrentBufferIndex] = accuracy;
        this.mRawTimestampBuffers[this.mActiveBuffersIndex][this.mCurrentBufferIndex] = rawTimestamp;
        this.mTimestampBuffers[this.mActiveBuffersIndex][this.mCurrentBufferIndex] = normalizedTimestamp;

        this.mCurrentBufferIndex += 1;
    }

    private void saveBuffer(final int bufferIndex, final int bufferSize) {
        final Accelerometer me = this;

        me.mLatestTimestamp = System.currentTimeMillis();

        Runnable r = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                try {
                    me.mDatabase.beginTransaction();

                    for (int i = 0; i < bufferSize; i++) {
                        ContentValues values = new ContentValues();

                        values.put(Accelerometer.HISTORY_X, me.mXValueBuffers[bufferIndex][i]);
                        values.put(Accelerometer.HISTORY_Y, me.mYValueBuffers[bufferIndex][i]);
                        values.put(Accelerometer.HISTORY_Z, me.mZValueBuffers[bufferIndex][i]);
                        values.put(Accelerometer.HISTORY_OBSERVED, me.mTimestampBuffers[bufferIndex][i]);
                        values.put(Accelerometer.HISTORY_RAW_TIMESTAMP, me.mRawTimestampBuffers[bufferIndex][i]);
                        values.put(Accelerometer.HISTORY_ACCURACY, me.mAccuracyBuffers[bufferIndex][i]);

                        me.mDatabase.insert(Accelerometer.TABLE_HISTORY, null, values);
                    }

                    me.mDatabase.setTransactionSuccessful();
                } finally {
                    me.mDatabase.endTransaction();
                }

                Bundle update = new Bundle();
                update.putLong(Accelerometer.HISTORY_OBSERVED, now);

                Bundle sensorReadings = new Bundle();

                sensorReadings.putFloatArray(Accelerometer.HISTORY_X, me.mXValueBuffers[bufferIndex]);
                sensorReadings.putFloatArray(Accelerometer.HISTORY_Y, me.mYValueBuffers[bufferIndex]);
                sensorReadings.putFloatArray(Accelerometer.HISTORY_Z, me.mZValueBuffers[bufferIndex]);
                sensorReadings.putLongArray(Accelerometer.HISTORY_RAW_TIMESTAMP, me.mRawTimestampBuffers[bufferIndex]);
                sensorReadings.putLongArray(Accelerometer.HISTORY_OBSERVED, me.mTimestampBuffers[bufferIndex]);
                sensorReadings.putIntArray(Accelerometer.HISTORY_ACCURACY, me.mAccuracyBuffers[bufferIndex]);

                update.putBundle(SensorGenerator.SENSOR_DATA, sensorReadings);
                SensorGenerator.addSensorMetadata(update, me.mSensor);

                Generators.getInstance(me.mContext).notifyGeneratorUpdated(Accelerometer.GENERATOR_IDENTIFIER, update);

                if (now - me.mLastCleanup > me.mCleanupInterval) {
                    me.mLastCleanup = now;

                    long start = (now - (24 * 60 * 60 * 1000)) * 1000 * 1000;

                    String where = Accelerometer.HISTORY_OBSERVED + " < ?";
                    String[] args = { "" + start };

                    me.mDatabase.delete(Accelerometer.TABLE_HISTORY, where, args);
                }
            }
        };

        Thread t = new Thread(r, "accelerometer-save-buffer");
        t.start();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int newAccuracy) {
        // Do nothing...
    }
}
