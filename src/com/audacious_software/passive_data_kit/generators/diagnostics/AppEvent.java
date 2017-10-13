package com.audacious_software.passive_data_kit.generators.diagnostics;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.activities.DataDisclosureDetailActivity;
import com.audacious_software.passive_data_kit.activities.generators.DataPointViewHolder;
import com.audacious_software.passive_data_kit.activities.generators.GeneratorViewHolder;
import com.audacious_software.passive_data_kit.diagnostics.DiagnosticAction;
import com.audacious_software.passive_data_kit.generators.Generator;
import com.audacious_software.passive_data_kit.generators.Generators;
import com.audacious_software.pdk.passivedatakit.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppEvent extends Generator{
    private static final String GENERATOR_IDENTIFIER = "pdk-app-event";

    private static final String ENABLED = "com.audacious_software.passive_data_kit.generators.diagnostics.AppEvent.ENABLED";
    private static final boolean ENABLED_DEFAULT = true;

    private static final String DATA_RETENTION_PERIOD = "com.audacious_software.passive_data_kit.generators.diagnostics.AppEvent.DATA_RETENTION_PERIOD";
    private static final long DATA_RETENTION_PERIOD_DEFAULT = (60 * 24 * 60 * 60 * 1000);

    private static final String DATABASE_PATH = "pdk-app-event.sqlite";
    private static final int DATABASE_VERSION = 1;

    private static final String HISTORY_OBSERVED = "observed";
    private static final String HISTORY_EVENT_NAME = "event_name";
    private static final String HISTORY_EVENT_DETAILS = "event_details";
    private static final String TABLE_HISTORY = "history";

    private static final int CARD_PAGE_SIZE = 8;
    private static final int CARD_MAX_PAGES = 8;

    private static final String DETAILS_THROWABLE_STACKTRACE = "stacktrace";
    private static final String DETAILS_THROWABLE_MESSAGE = "message";
    private static final String EVENT_LOG_THROWABLE = "log_throwable";

    private static AppEvent sInstance = null;

    private SQLiteDatabase mDatabase = null;

    private boolean mWorking = false;
    private List<HashMap<String, Map<String, ?>>> mPending = new ArrayList<>();

    private int mPage = 0;
    private long mLastTimestamp = -1;

    @SuppressWarnings("unused")
    public static String generatorIdentifier() {
        return AppEvent.GENERATOR_IDENTIFIER;
    }

    public static AppEvent getInstance(Context context) {
        if (AppEvent.sInstance == null) {
            AppEvent.sInstance = new AppEvent(context.getApplicationContext());
        }

        return AppEvent.sInstance;
    }

    public AppEvent(Context context) {
        super(context);
    }

    @SuppressWarnings("unused")
    public static void start(final Context context) {
        AppEvent.getInstance(context).startGenerator();
    }

    @SuppressWarnings("WeakerAccess")
    public static String getGeneratorTitle(Context context) {
        return context.getString(R.string.generator_app_events);
    }

    private void startGenerator() {
        Generators.getInstance(this.mContext).registerCustomViewClass(AppEvent.GENERATOR_IDENTIFIER, AppEvent.class);

        File path = PassiveDataKit.getGeneratorsStorage(this.mContext);

        path = new File(path, AppEvent.DATABASE_PATH);

        this.mWorking = true;

        this.mDatabase = SQLiteDatabase.openOrCreateDatabase(path, null);

        int version = this.getDatabaseVersion(this.mDatabase);

        switch (version) {
            case 0:
                this.mDatabase.execSQL(this.mContext.getString(R.string.pdk_generator_app_events_create_history_table));
        }

        if (version != AppEvent.DATABASE_VERSION) {
            this.setDatabaseVersion(this.mDatabase, AppEvent.DATABASE_VERSION);
        }

        this.mWorking = false;

        this.flushCachedData();
    }

    @SuppressWarnings("unused")
    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = Generators.getInstance(context).getSharedPreferences(context);

        return prefs.getBoolean(AppEvent.ENABLED, AppEvent.ENABLED_DEFAULT);
    }

    @SuppressWarnings({"UnusedParameters", "unused"})
    public static boolean isRunning(Context context) {
        return (AppEvent.sInstance != null);
    }

    @SuppressWarnings({"UnusedParameters", "unused"})
    public static ArrayList<DiagnosticAction> diagnostics(Context context) {
        return new ArrayList<>();
    }

    @SuppressWarnings("unused")
    public static void bindDisclosureViewHolder(final GeneratorViewHolder holder) {
        TextView generatorLabel = (TextView) holder.itemView.findViewById(R.id.label_generator);

        generatorLabel.setText(AppEvent.getGeneratorTitle(holder.itemView.getContext()));
    }

    @SuppressWarnings("unused")
    public static void bindViewHolder(final DataPointViewHolder holder) {
        final Activity activity = (Activity) holder.itemView.getContext();
        final AppEvent generator = AppEvent.getInstance(activity);

        final View cardContent = holder.itemView.findViewById(R.id.card_content);
        final View cardSizer = holder.itemView.findViewById(R.id.card_sizer);
        final View cardEmpty = holder.itemView.findViewById(R.id.card_empty);
        final TextView dateLabel = (TextView) holder.itemView.findViewById(R.id.generator_data_point_date);

        cardContent.setVisibility(View.GONE);
        cardEmpty.setVisibility(View.VISIBLE);
        cardSizer.setVisibility(View.INVISIBLE);

        dateLabel.setText(R.string.label_never_pdk);

        Runnable r = new Runnable() {
            @Override
            public void run() {
                final ArrayList<Object[]> events = new ArrayList<>();

                Cursor c = generator.mDatabase.query(AppEvent.TABLE_HISTORY, null, null, null, null, null, AppEvent.HISTORY_OBSERVED + " DESC", "" + (AppEvent.CARD_PAGE_SIZE * AppEvent.CARD_MAX_PAGES));

                while (c.moveToNext()) {
                    String event = c.getString(c.getColumnIndex(AppEvent.HISTORY_EVENT_NAME));
                    long timestamp = c.getLong(c.getColumnIndex(AppEvent.HISTORY_OBSERVED));

                    Object[] values = { event, timestamp };

                    events.add(values);
                }

                c.close();

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (events.size() > 0) {
                            cardContent.setVisibility(View.VISIBLE);
                            cardEmpty.setVisibility(View.GONE);
                            cardSizer.setVisibility(View.INVISIBLE);

                            Object[] values = events.get(0);

                            dateLabel.setText(Generator.formatTimestamp(activity, ((long) values[1]) / 1000));

                            final int pages = (int) Math.ceil(((double) events.size()) / AppEvent.CARD_PAGE_SIZE);

                            final AppEvent appEvent = AppEvent.getInstance(holder.itemView.getContext());

                            ViewPager pager = (ViewPager) holder.itemView.findViewById(R.id.content_pager);

                            PagerAdapter adapter = new PagerAdapter() {
                                @Override
                                public int getCount() {
                                    return pages;
                                }

                                @Override
                                public boolean isViewFromObject(View view, Object content) {
                                    return view.getTag().equals(content);
                                }

                                public void destroyItem(ViewGroup container, int position, Object content) {
                                    int toRemove = -1;

                                    for (int i = 0; i < container.getChildCount(); i++) {
                                        View child = container.getChildAt(i);

                                        if (this.isViewFromObject(child, content))
                                            toRemove = i;
                                    }

                                    if (toRemove >= 0)
                                        container.removeViewAt(toRemove);
                                }

                                @SuppressWarnings("UnusedAssignment")
                                public Object instantiateItem(ViewGroup container, int position) {
                                    LinearLayout list = (LinearLayout) LayoutInflater.from(container.getContext()).inflate(R.layout.card_generator_app_event_page, container, false);

                                    int listPosition = AppEvent.CARD_PAGE_SIZE * position;

                                    for (int i = 0; i < AppEvent.CARD_PAGE_SIZE && listPosition + i < events.size(); i++) {
                                        Object[] values = events.get(listPosition + i);

                                        String event = (String) values[0];
                                        long timestamp = (long) values[1];

                                        LinearLayout row = null;

                                        switch (i) {
                                            case 0:
                                                row = (LinearLayout) list.findViewById(R.id.app_event_row_0);
                                                break;
                                            case 1:
                                                row = (LinearLayout) list.findViewById(R.id.app_event_row_1);
                                                break;
                                            case 2:
                                                row = (LinearLayout) list.findViewById(R.id.app_event_row_2);
                                                break;
                                            case 3:
                                                row = (LinearLayout) list.findViewById(R.id.app_event_row_3);
                                                break;
                                            case 4:
                                                row = (LinearLayout) list.findViewById(R.id.app_event_row_4);
                                                break;
                                            case 5:
                                                row = (LinearLayout) list.findViewById(R.id.app_event_row_5);
                                                break;
                                            case 6:
                                                row = (LinearLayout) list.findViewById(R.id.app_event_row_6);
                                                break;
                                            default:
                                                row = (LinearLayout) list.findViewById(R.id.app_event_row_7);
                                                break;
                                        }

                                        TextView eventName = (TextView) row.findViewById(R.id.app_event_row_event_name);
                                        TextView eventWhen = (TextView) row.findViewById(R.id.app_event_row_event_when);

                                        eventName.setText(event);
                                        eventWhen.setText(Generator.formatTimestamp(activity, timestamp / 1000));
                                    }

                                    list.setTag("" + position);

                                    container.addView(list);

                                    return "" + list.getTag();
                                }
                            };

                            pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                                @Override
                                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

                                }

                                @Override
                                public void onPageSelected(int position) {
                                    appEvent.mPage = position;
                                }

                                @Override
                                public void onPageScrollStateChanged(int state) {

                                }
                            });

                            pager.setAdapter(adapter);

                            pager.setCurrentItem(appEvent.mPage);
                        } else {
                            cardContent.setVisibility(View.GONE);
                            cardEmpty.setVisibility(View.VISIBLE);
                            cardSizer.setVisibility(View.INVISIBLE);

                            dateLabel.setText(R.string.label_never_pdk);
                        }
                    }
                });
            }
        };

        Thread t = new Thread(r);
        t.start();
    }

    @SuppressWarnings("unused")
    public static View fetchView(ViewGroup parent) {
        return LayoutInflater.from(parent.getContext()).inflate(R.layout.card_generator_app_event, parent, false);
    }

    @Override
    public List<Bundle> fetchPayloads() {
        return new ArrayList<>();
    }

    @SuppressWarnings("unused")
    public static long latestPointGenerated(Context context) {
        AppEvent me = AppEvent.getInstance(context);

        if (me.mLastTimestamp != -1) {
            return me.mLastTimestamp;
        }

        Cursor c = me.mDatabase.query(AppEvent.TABLE_HISTORY, null, null, null, null, null, AppEvent.HISTORY_OBSERVED + " DESC");

        if (c.moveToNext()) {
            me.mLastTimestamp = c.getLong(c.getColumnIndex(AppEvent.HISTORY_OBSERVED));
        }

        c.close();

        return me.mLastTimestamp;
    }

    public Cursor queryHistory(String[] cols, String where, String[] args, String orderBy) {
        return this.mDatabase.query(AppEvent.TABLE_HISTORY, cols, where, args, null, null, orderBy);
    }

    @SuppressWarnings("UnusedReturnValue")
    public boolean logEvent(String eventName, Map<String, ?> eventDetails) {
        synchronized (this.mPending) {
            HashMap<String, Map<String, ?>> item = new HashMap<>();
            item.put(eventName, eventDetails);

            this.mPending.add(item);

            if (this.mWorking || this.mDatabase == null) {
                return true;
            }

            this.mWorking = true;

            while (this.mPending.size() > 0) {
                HashMap<String, Map<String, ?>> savedItem = this.mPending.remove(0);

                if (savedItem != null) {
                    for (String savedEventName : savedItem.keySet()) {
                        Map<String, ?> savedEventDetails = savedItem.get(savedEventName);

                        try {
                            long now = System.currentTimeMillis();

                            ContentValues values = new ContentValues();
                            values.put(AppEvent.HISTORY_OBSERVED, now);
                            values.put(AppEvent.HISTORY_EVENT_NAME, savedEventName);

                            Bundle detailsBundle = new Bundle();
                            JSONObject detailsJson = new JSONObject();

                            for (String key : savedEventDetails.keySet()) {
                                Object value = savedEventDetails.get(key);

                                if (value instanceof Double) {
                                    Double doubleValue = ((Double) value);

                                    detailsBundle.putDouble(key, doubleValue);
                                    detailsJson.put(key, doubleValue.doubleValue());
                                } else if (value instanceof Float) {
                                    Float floatValue = ((Float) value);

                                    detailsBundle.putDouble(key, floatValue.doubleValue());
                                    detailsJson.put(key, floatValue.doubleValue());
                                } else if (value instanceof Long) {
                                    Long longValue = ((Long) value);

                                    detailsBundle.putLong(key, longValue);
                                    detailsJson.put(key, longValue.longValue());
                                } else if (value instanceof Integer) {
                                    Integer intValue = ((Integer) value);

                                    detailsBundle.putLong(key, intValue.longValue());
                                    detailsJson.put(key, intValue.longValue());
                                } else if (value instanceof String) {
                                    detailsBundle.putString(key, value.toString());
                                    detailsJson.put(key, value.toString());
                                } else if (value instanceof Boolean) {
                                    detailsBundle.putBoolean(key, ((Boolean) value));
                                    detailsJson.put(key, ((Boolean) value).booleanValue());
                                } else if (value == null) {
                                    throw new NullPointerException("Value is null.");
                                } else {
                                    detailsBundle.putString(key, "Unknown Class: " + value.getClass().getCanonicalName());
                                    detailsJson.put(key, "Unknown Class: " + value.getClass().getCanonicalName());
                                }
                            }

                            values.put(AppEvent.HISTORY_EVENT_DETAILS, detailsJson.toString(2));

                            this.mDatabase.insert(AppEvent.TABLE_HISTORY, null, values);

                            Bundle update = new Bundle();
                            update.putLong(AppEvent.HISTORY_OBSERVED, now);
                            update.putString(AppEvent.HISTORY_EVENT_NAME, values.getAsString(AppEvent.HISTORY_EVENT_NAME));
                            update.putBundle(AppEvent.HISTORY_EVENT_DETAILS, detailsBundle);

                            Generators.getInstance(this.mContext).notifyGeneratorUpdated(AppEvent.GENERATOR_IDENTIFIER, update);

                            this.mWorking = false;

                            return true;
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        this.mWorking = false;

        return true;
    }

    public void logThrowable(Throwable t) {
        t.printStackTrace();

        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);

        t.printStackTrace(writer);
        writer.flush();

        HashMap<String, String> details = new HashMap<>();
        details.put(AppEvent.DETAILS_THROWABLE_STACKTRACE, out.toString());

        if (t.getMessage() != null) {
            details.put(AppEvent.DETAILS_THROWABLE_MESSAGE, t.getMessage());
        } else {
            details.put(AppEvent.DETAILS_THROWABLE_MESSAGE, "(No message provided.)");
        }

        this.logEvent(AppEvent.EVENT_LOG_THROWABLE, details);
    }

    @SuppressWarnings("unused")
    public static List<DataDisclosureDetailActivity.Action> getDisclosureActions(final Context context) {
        List<DataDisclosureDetailActivity.Action> actions = new ArrayList<>();

        DataDisclosureDetailActivity.Action disclosure = new DataDisclosureDetailActivity.Action();

        disclosure.title = context.getString(R.string.label_data_collection_description);
        disclosure.subtitle = context.getString(R.string.label_data_collection_description_more);

        WebView disclosureView = new WebView(context);
        disclosureView.loadUrl("file:///android_asset/html/passive_data_kit/generator_app_events_disclosure.html");

        disclosure.view = disclosureView;

        actions.add(disclosure);

        return actions;
    }

    @SuppressWarnings("unused")
    public static View getDisclosureDataView(final GeneratorViewHolder holder) {
        final Context context = holder.itemView.getContext();

        WebView disclosureView = new WebView(context);
        disclosureView.loadUrl("file:///android_asset/html/passive_data_kit/generator_app_events_disclosure.html");

        return disclosureView;
    }

    @Override
    protected void flushCachedData() {
        this.mWorking = true;

        final AppEvent me = this;

        Runnable r = new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me.mContext);

                long retentionPeriod = prefs.getLong(AppEvent.DATA_RETENTION_PERIOD, AppEvent.DATA_RETENTION_PERIOD_DEFAULT);

                long start = System.currentTimeMillis() - retentionPeriod;

                String where = AppEvent.HISTORY_OBSERVED + " < ?";
                String[] args = { "" + start };

                me.mDatabase.delete(AppEvent.TABLE_HISTORY, where, args);

                me.mWorking = false;
            }
        };

        Thread t = new Thread(r);
        t.start();
    }

    @Override
    public void setCachedDataRetentionPeriod(long period) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.mContext);
        SharedPreferences.Editor e = prefs.edit();

        e.putLong(AppEvent.DATA_RETENTION_PERIOD, period);

        e.apply();
    }
}
