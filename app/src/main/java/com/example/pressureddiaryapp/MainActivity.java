package com.example.pressureddiaryapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private EditText sysInput, diaInput, pulseInput, noteInput;
    private TextView resultText, statsText;
    private FrameLayout chartContainer;
    private Button saveButton, reminderButton, historyButton, clearButton;

    private SharedPreferences preferences;

    private static final String PREFS_NAME = "pressure_diary";
    private static final String RECORDS_KEY = "records";
    private static final String REMINDERS_KEY = "reminders";

    private static final int NOTIFICATION_PERMISSION_CODE = 300;
    private static final String CHANNEL_ID = "pressure_reminder_channel";
    private static final String WORK_TAG = "pressure_reminders";
    private static final String WORK_NAME_PREFIX = "pressure_reminder_";
    private static final String REMINDER_ID_KEY = "REMINDER_ID";

    private final ArrayList<PressureRecord> records = new ArrayList<>();
    private final ArrayList<ReminderItem> reminders = new ArrayList<>();

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());

    private static class PressureRecord {
        long id;
        long time;
        int sys;
        int dia;
        int pulse;
        String note;

        PressureRecord(long id, long time, int sys, int dia, int pulse, String note) {
            this.id = id;
            this.time = time;
            this.sys = sys;
            this.dia = dia;
            this.pulse = pulse;
            this.note = note;
        }

        String toStorageString() {
            return id + "|" + time + "|" + sys + "|" + dia + "|" + pulse + "|" + Uri.encode(note);
        }

        static PressureRecord fromStorageString(String value) {
            try {
                String[] p = value.split("\\|", -1);

                return new PressureRecord(
                        Long.parseLong(p[0]),
                        Long.parseLong(p[1]),
                        Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]),
                        p.length >= 6 ? Uri.decode(p[5]) : ""
                );
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static class ReminderItem {
        long id;
        int hour;
        int minute;
        int daysMask;

        ReminderItem(long id, int hour, int minute, int daysMask) {
            this.id = id;
            this.hour = hour;
            this.minute = minute;
            this.daysMask = daysMask;
        }

        String toStorageString() {
            return id + "|" + hour + "|" + minute + "|" + daysMask;
        }

        static ReminderItem fromStorageString(String value) {
            try {
                String[] p = value.split("\\|");

                return new ReminderItem(
                        Long.parseLong(p[0]),
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]),
                        Integer.parseInt(p[3])
                );
            } catch (Exception e) {
                return null;
            }
        }

        String getTimeText() {
            return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sysInput = findViewById(R.id.sysInput);
        diaInput = findViewById(R.id.diaInput);
        pulseInput = findViewById(R.id.pulseInput);
        noteInput = findViewById(R.id.noteInput);

        resultText = findViewById(R.id.resultText);
        statsText = findViewById(R.id.statsText);
        chartContainer = findViewById(R.id.chartContainer);

        saveButton = findViewById(R.id.saveButton);
        reminderButton = findViewById(R.id.reminderButton);
        historyButton = findViewById(R.id.historyButton);
        clearButton = findViewById(R.id.clearButton);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        styleMainCards();

        requestNotificationPermissionIfNeeded();
        createNotificationChannel();

        loadRecords();
        loadReminders();

        renderScreen();
        updateReminderButtonText();
        scheduleAllReminders(this);

        saveButton.setOnClickListener(v -> savePressureRecord());
        reminderButton.setOnClickListener(v -> showRemindersWindow());
        historyButton.setOnClickListener(v -> showHistoryDialog());

        clearButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Очистить дневник?")
                .setMessage("Все записи давления будут удалены.")
                .setPositiveButton("Очистить", (dialog, which) -> {
                    records.clear();
                    saveRecords();
                    renderScreen();
                    Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show());
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (preferences != null) {
            loadReminders();
            scheduleAllReminders(this);
            updateReminderButtonText();
        }
    }

    private void styleMainCards() {
        findViewById(R.id.inputCard).setBackground(round(Color.rgb(17, 24, 39), 26));
        resultText.setBackground(round(Color.rgb(17, 24, 39), 26));
        statsText.setBackground(round(Color.rgb(17, 24, 39), 26));
        chartContainer.setBackground(round(Color.rgb(17, 24, 39), 26));
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void savePressureRecord() {
        String sysText = sysInput.getText().toString().trim();
        String diaText = diaInput.getText().toString().trim();
        String pulseText = pulseInput.getText().toString().trim();
        String note = noteInput.getText().toString().trim();

        if (sysText.isEmpty() || diaText.isEmpty() || pulseText.isEmpty()) {
            Toast.makeText(this, "Заполните давление и пульс", Toast.LENGTH_SHORT).show();
            return;
        }

        int sys;
        int dia;
        int pulse;

        try {
            sys = Integer.parseInt(sysText);
            dia = Integer.parseInt(diaText);
            pulse = Integer.parseInt(pulseText);
        } catch (Exception e) {
            Toast.makeText(this, "Введите числа", Toast.LENGTH_SHORT).show();
            return;
        }

        if (sys < 60 || sys > 260 || dia < 40 || dia > 180 || pulse < 30 || pulse > 220) {
            Toast.makeText(this, "Проверьте диапазон значений", Toast.LENGTH_LONG).show();
            return;
        }

        if (dia >= sys) {
            Toast.makeText(this, "Нижнее давление должно быть меньше верхнего", Toast.LENGTH_LONG).show();
            return;
        }

        PressureRecord record = new PressureRecord(
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                sys,
                dia,
                pulse,
                note
        );

        records.add(0, record);
        saveRecords();

        sysInput.setText("");
        diaInput.setText("");
        pulseInput.setText("");
        noteInput.setText("");

        resultText.setText(getPressureInterpretation(sys, dia));
        renderScreen();

        Toast.makeText(this, "Измерение сохранено", Toast.LENGTH_SHORT).show();
    }

    private String getPressureInterpretation(int sys, int dia) {
        if (sys > 180 || dia > 120) {
            return "Результат: " + sys + "/" + dia +
                    "\nОчень высокое давление. При плохом самочувствии обратитесь за медицинской помощью.";
        }

        if (sys >= 140 || dia >= 90) {
            return "Результат: " + sys + "/" + dia +
                    "\nВысокое давление. Рекомендуется наблюдать динамику и обратиться к врачу.";
        }

        if (sys >= 130 || dia >= 80) {
            return "Результат: " + sys + "/" + dia +
                    "\nПовышенное давление. Продолжайте вести дневник.";
        }

        if (sys >= 120 && sys <= 129 && dia < 80) {
            return "Результат: " + sys + "/" + dia +
                    "\nНемного повышенное верхнее давление.";
        }

        if (sys < 120 && dia < 80) {
            return "Результат: " + sys + "/" + dia +
                    "\nНормальное давление.";
        }

        return "Результат: " + sys + "/" + dia +
                "\nНеобычное сочетание показателей. Лучше повторить измерение.";
    }

    private void renderScreen() {
        renderStats();
        renderChart();
    }

    private void renderStats() {
        if (records.isEmpty()) {
            statsText.setText("Статистика появится после первой записи");
            return;
        }

        int count = records.size();
        int sumSys = 0;
        int sumDia = 0;
        int sumPulse = 0;

        int maxSys = records.get(0).sys;
        int minSys = records.get(0).sys;

        for (PressureRecord r : records) {
            sumSys += r.sys;
            sumDia += r.dia;
            sumPulse += r.pulse;

            if (r.sys > maxSys) maxSys = r.sys;
            if (r.sys < minSys) minSys = r.sys;
        }

        PressureRecord last = records.get(0);

        statsText.setText(
                "Последнее: " + last.sys + "/" + last.dia + ", пульс " + last.pulse +
                        "\nСреднее: " + (sumSys / count) + "/" + (sumDia / count) +
                        "\nСредний пульс: " + (sumPulse / count) +
                        "\nЗаписей: " + count +
                        "\nМакс/мин верхнее: " + maxSys + " / " + minSys
        );
    }

    private void renderChart() {
        chartContainer.removeAllViews();

        chartContainer.addView(
                new PressureChartView(this, records),
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
    }

    private void showRemindersWindow() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 36, 24, 24);
        root.setBackgroundColor(Color.rgb(7, 17, 31));

        TextView title = new TextView(this);
        title.setText("Напоминания");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 18);

        Button addButton = new Button(this);
        addButton.setText("Добавить напоминание");
        addButton.setTextColor(Color.WHITE);
        addButton.setBackgroundColor(Color.rgb(5, 150, 105));

        Button backButton = new Button(this);
        backButton.setText("Назад");
        backButton.setTextColor(Color.WHITE);
        backButton.setBackgroundColor(Color.rgb(51, 65, 85));

        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        if (reminders.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Напоминаний пока нет");
            empty.setTextColor(Color.rgb(148, 163, 184));
            empty.setTextSize(18);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 50, 0, 50);
            list.addView(empty);
        } else {
            for (ReminderItem reminder : reminders) {
                list.addView(createReminderCard(reminder, dialog));
            }
        }

        scrollView.addView(list);

        addButton.setOnClickListener(v -> {
            dialog.dismiss();
            showReminderEditor(null);
        });

        backButton.setOnClickListener(v -> dialog.dismiss());

        root.addView(title);
        root.addView(addButton);

        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        backParams.setMargins(0, 10, 0, 16);

        root.addView(backButton, backParams);

        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        dialog.setContentView(root);

        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();

            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT
                );
            }
        });

        dialog.show();
    }

    private LinearLayout createReminderCard(ReminderItem reminder, Dialog parentDialog) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(22, 18, 22, 18);
        card.setBackground(round(Color.rgb(17, 24, 39), 24));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );

        cardParams.setMargins(0, 0, 0, 16);
        card.setLayoutParams(cardParams);

        TextView time = new TextView(this);
        time.setText(reminder.getTimeText());
        time.setTextColor(Color.WHITE);
        time.setTextSize(26);
        time.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView days = new TextView(this);
        days.setText("Дни: " + getDaysText(reminder.daysMask));
        days.setTextColor(Color.rgb(148, 163, 184));
        days.setTextSize(16);
        days.setPadding(0, 6, 0, 12);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button edit = new Button(this);
        edit.setText("Изменить");
        edit.setTextColor(Color.WHITE);
        edit.setBackgroundColor(Color.rgb(37, 99, 235));

        Button delete = new Button(this);
        delete.setText("Удалить");
        delete.setTextColor(Color.WHITE);
        delete.setBackgroundColor(Color.rgb(185, 28, 28));

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        );
        btnParams.setMargins(4, 0, 4, 0);

        edit.setLayoutParams(btnParams);
        delete.setLayoutParams(btnParams);

        edit.setOnClickListener(v -> {
            parentDialog.dismiss();
            showReminderEditor(reminder);
        });

        delete.setOnClickListener(v -> {
            cancelReminder(this, reminder);
            reminders.remove(reminder);
            saveReminders();
            updateReminderButtonText();

            Toast.makeText(this, "Напоминание удалено", Toast.LENGTH_SHORT).show();

            parentDialog.dismiss();
            showRemindersWindow();
        });

        buttons.addView(edit);
        buttons.addView(delete);

        card.addView(time);
        card.addView(days);
        card.addView(buttons);

        return card;
    }

    private void showReminderEditor(ReminderItem oldReminder) {
        final int[] selectedHour = {oldReminder == null ? 8 : oldReminder.hour};
        final int[] selectedMinute = {oldReminder == null ? 0 : oldReminder.minute};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 20, 36, 10);

        TextView timeText = new TextView(this);
        timeText.setText("Время: " + String.format(Locale.getDefault(), "%02d:%02d", selectedHour[0], selectedMinute[0]));
        timeText.setTextSize(21);
        timeText.setGravity(Gravity.CENTER);
        timeText.setTypeface(null, android.graphics.Typeface.BOLD);
        timeText.setPadding(0, 10, 0, 18);

        Button timeButton = new Button(this);
        timeButton.setText("Выбрать время");

        CheckBox monday = new CheckBox(this);
        CheckBox tuesday = new CheckBox(this);
        CheckBox wednesday = new CheckBox(this);
        CheckBox thursday = new CheckBox(this);
        CheckBox friday = new CheckBox(this);
        CheckBox saturday = new CheckBox(this);
        CheckBox sunday = new CheckBox(this);

        monday.setText("Понедельник");
        tuesday.setText("Вторник");
        wednesday.setText("Среда");
        thursday.setText("Четверг");
        friday.setText("Пятница");
        saturday.setText("Суббота");
        sunday.setText("Воскресенье");

        int mask = oldReminder == null ? 127 : oldReminder.daysMask;

        monday.setChecked((mask & 1) != 0);
        tuesday.setChecked((mask & 2) != 0);
        wednesday.setChecked((mask & 4) != 0);
        thursday.setChecked((mask & 8) != 0);
        friday.setChecked((mask & 16) != 0);
        saturday.setChecked((mask & 32) != 0);
        sunday.setChecked((mask & 64) != 0);

        timeButton.setOnClickListener(v -> {
            TimePickerDialog picker = new TimePickerDialog(
                    this,
                    (view, hourOfDay, minute) -> {
                        selectedHour[0] = hourOfDay;
                        selectedMinute[0] = minute;

                        timeText.setText(
                                "Время: " + String.format(Locale.getDefault(), "%02d:%02d", selectedHour[0], selectedMinute[0])
                        );
                    },
                    selectedHour[0],
                    selectedMinute[0],
                    true
            );

            picker.show();
        });

        root.addView(timeText);
        root.addView(timeButton);
        root.addView(monday);
        root.addView(tuesday);
        root.addView(wednesday);
        root.addView(thursday);
        root.addView(friday);
        root.addView(saturday);
        root.addView(sunday);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(oldReminder == null ? "Новое напоминание" : "Изменить напоминание")
                .setView(root)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

            save.setOnClickListener(v -> {
                int daysMask = 0;

                if (monday.isChecked()) daysMask |= 1;
                if (tuesday.isChecked()) daysMask |= 2;
                if (wednesday.isChecked()) daysMask |= 4;
                if (thursday.isChecked()) daysMask |= 8;
                if (friday.isChecked()) daysMask |= 16;
                if (saturday.isChecked()) daysMask |= 32;
                if (sunday.isChecked()) daysMask |= 64;

                if (daysMask == 0) {
                    Toast.makeText(this, "Выберите хотя бы один день", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (oldReminder == null) {
                    ReminderItem item = new ReminderItem(
                            System.currentTimeMillis(),
                            selectedHour[0],
                            selectedMinute[0],
                            daysMask
                    );

                    reminders.add(item);
                    saveReminders();
                    scheduleReminder(this, item);

                    Toast.makeText(this, "Напоминание добавлено", Toast.LENGTH_SHORT).show();
                } else {
                    cancelReminder(this, oldReminder);

                    oldReminder.hour = selectedHour[0];
                    oldReminder.minute = selectedMinute[0];
                    oldReminder.daysMask = daysMask;

                    saveReminders();
                    scheduleReminder(this, oldReminder);

                    Toast.makeText(this, "Напоминание изменено", Toast.LENGTH_SHORT).show();
                }

                updateReminderButtonText();
                dialog.dismiss();
                showRemindersWindow();
            });
        });

        dialog.show();
    }

    private void updateReminderButtonText() {
        if (reminders.isEmpty()) {
            reminderButton.setText("Настроить напоминания");
        } else {
            reminderButton.setText("Напоминания: " + reminders.size());
        }
    }

    private static void scheduleAllReminders(Context context) {
        ArrayList<ReminderItem> list = loadRemindersStatic(context);

        for (ReminderItem item : list) {
            scheduleReminder(context, item);
        }
    }

    private static void scheduleReminder(Context context, ReminderItem reminder) {
        long triggerTime = calculateNextReminderTime(reminder);
        long delayMillis = Math.max(0, triggerTime - System.currentTimeMillis());

        Data data = new Data.Builder()
                .putLong(REMINDER_ID_KEY, reminder.id)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReminderWorker.class)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag(WORK_TAG)
                .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        getWorkName(reminder.id),
                        ExistingWorkPolicy.REPLACE,
                        request
                );
    }

    private static void cancelReminder(Context context, ReminderItem reminder) {
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(getWorkName(reminder.id));
    }

    private static String getWorkName(long reminderId) {
        return WORK_NAME_PREFIX + reminderId;
    }

    private static long calculateNextReminderTime(ReminderItem reminder) {
        java.util.Calendar now = java.util.Calendar.getInstance();

        for (int i = 0; i <= 7; i++) {
            java.util.Calendar candidate = java.util.Calendar.getInstance();
            candidate.add(java.util.Calendar.DAY_OF_MONTH, i);
            candidate.set(java.util.Calendar.HOUR_OF_DAY, reminder.hour);
            candidate.set(java.util.Calendar.MINUTE, reminder.minute);
            candidate.set(java.util.Calendar.SECOND, 0);
            candidate.set(java.util.Calendar.MILLISECOND, 0);

            int bit = getDayBit(candidate.get(java.util.Calendar.DAY_OF_WEEK));

            if ((reminder.daysMask & bit) != 0 && candidate.getTimeInMillis() > now.getTimeInMillis()) {
                return candidate.getTimeInMillis();
            }
        }

        java.util.Calendar fallback = java.util.Calendar.getInstance();
        fallback.add(java.util.Calendar.DAY_OF_MONTH, 1);
        fallback.set(java.util.Calendar.HOUR_OF_DAY, reminder.hour);
        fallback.set(java.util.Calendar.MINUTE, reminder.minute);
        fallback.set(java.util.Calendar.SECOND, 0);
        fallback.set(java.util.Calendar.MILLISECOND, 0);

        return fallback.getTimeInMillis();
    }

    private static int getDayBit(int calendarDay) {
        switch (calendarDay) {
            case java.util.Calendar.MONDAY:
                return 1;
            case java.util.Calendar.TUESDAY:
                return 2;
            case java.util.Calendar.WEDNESDAY:
                return 4;
            case java.util.Calendar.THURSDAY:
                return 8;
            case java.util.Calendar.FRIDAY:
                return 16;
            case java.util.Calendar.SATURDAY:
                return 32;
            case java.util.Calendar.SUNDAY:
                return 64;
            default:
                return 0;
        }
    }

    private String getDaysText(int daysMask) {
        if (daysMask == 127) return "каждый день";

        ArrayList<String> days = new ArrayList<>();

        if ((daysMask & 1) != 0) days.add("Пн");
        if ((daysMask & 2) != 0) days.add("Вт");
        if ((daysMask & 4) != 0) days.add("Ср");
        if ((daysMask & 8) != 0) days.add("Чт");
        if ((daysMask & 16) != 0) days.add("Пт");
        if ((daysMask & 32) != 0) days.add("Сб");
        if ((daysMask & 64) != 0) days.add("Вс");

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < days.size(); i++) {
            builder.append(days.get(i));

            if (i < days.size() - 1) {
                builder.append(", ");
            }
        }

        return builder.toString();
    }

    private void showHistoryDialog() {
        ScrollView scrollView = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 22, 22, 22);
        root.setBackgroundColor(Color.rgb(15, 23, 42));

        if (records.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Записей пока нет");
            empty.setTextColor(Color.WHITE);
            empty.setTextSize(18);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 40, 0, 40);
            root.addView(empty);
        } else {
            for (PressureRecord r : records) {
                root.addView(createHistoryCard(r));
            }
        }

        scrollView.addView(root);

        new AlertDialog.Builder(this)
                .setTitle("История измерений")
                .setView(scrollView)
                .setNegativeButton("Назад", null)
                .show();
    }

    private LinearLayout createHistoryCard(PressureRecord record) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(18, 16, 18, 16);
        card.setBackground(round(Color.rgb(17, 24, 39), 24));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );

        cardParams.setMargins(0, 0, 0, 16);
        card.setLayoutParams(cardParams);

        TextView main = new TextView(this);
        main.setText(record.sys + "/" + record.dia + "  •  пульс " + record.pulse);
        main.setTextColor(Color.WHITE);
        main.setTextSize(22);
        main.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView date = new TextView(this);
        date.setText(dateFormat.format(new Date(record.time)));
        date.setTextColor(Color.rgb(148, 163, 184));
        date.setTextSize(14);
        date.setPadding(0, 6, 0, 6);

        TextView note = new TextView(this);
        note.setText(record.note.isEmpty() ? "Без заметки" : record.note);
        note.setTextColor(Color.rgb(203, 213, 225));
        note.setTextSize(15);
        note.setPadding(0, 0, 0, 10);

        Button delete = new Button(this);
        delete.setText("Удалить запись");
        delete.setTextColor(Color.WHITE);
        delete.setBackgroundColor(Color.rgb(185, 28, 28));

        delete.setOnClickListener(v -> {
            records.remove(record);
            saveRecords();
            renderScreen();
            card.setVisibility(android.view.View.GONE);

            Toast.makeText(this, "Запись удалена", Toast.LENGTH_SHORT).show();
        });

        card.addView(main);
        card.addView(date);
        card.addView(note);
        card.addView(delete);

        return card;
    }

    private void loadRecords() {
        records.clear();

        String saved = preferences.getString(RECORDS_KEY, "");

        if (saved.isEmpty()) return;

        String[] lines = saved.split("\n");

        for (String line : lines) {
            PressureRecord record = PressureRecord.fromStorageString(line);

            if (record != null) {
                records.add(record);
            }
        }
    }

    private void saveRecords() {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < records.size(); i++) {
            builder.append(records.get(i).toStorageString());

            if (i < records.size() - 1) {
                builder.append("\n");
            }
        }

        preferences.edit()
                .putString(RECORDS_KEY, builder.toString())
                .apply();
    }

    private void loadReminders() {
        reminders.clear();
        reminders.addAll(loadRemindersStatic(this));
    }

    private static ArrayList<ReminderItem> loadRemindersStatic(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String saved = prefs.getString(REMINDERS_KEY, "");

        ArrayList<ReminderItem> list = new ArrayList<>();

        if (saved.isEmpty()) return list;

        String[] lines = saved.split("\n");

        for (String line : lines) {
            ReminderItem item = ReminderItem.fromStorageString(line);

            if (item != null) {
                list.add(item);
            }
        }

        return list;
    }

    private void saveReminders() {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < reminders.size(); i++) {
            builder.append(reminders.get(i).toStorageString());

            if (i < reminders.size() - 1) {
                builder.append("\n");
            }
        }

        preferences.edit()
                .putString(REMINDERS_KEY, builder.toString())
                .apply();
    }

    private static ReminderItem findReminderById(Context context, long id) {
        ArrayList<ReminderItem> list = loadRemindersStatic(context);

        for (ReminderItem item : list) {
            if (item.id == id) {
                return item;
            }
        }

        return null;
    }

    private void createNotificationChannel() {
        createNotificationChannelStatic(this);
    }

    private static void createNotificationChannelStatic(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Напоминания измерить давление",
                NotificationManager.IMPORTANCE_HIGH
        );

        channel.setDescription("Напоминания для дневника давления");
        channel.enableVibration(true);

        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_CODE
                );
            }
        }
    }

    public static class ReminderWorker extends Worker {
        public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            Context context = getApplicationContext();
            long reminderId = getInputData().getLong(REMINDER_ID_KEY, -1);
            ReminderItem reminder = findReminderById(context, reminderId);

            if (reminder == null) {
                return Result.success();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                scheduleReminder(context, reminder);
                return Result.success();
            }

            showPressureReminderNotification(context, reminderId);
            scheduleReminder(context, reminder);

            return Result.success();
        }
    }

    private static void showPressureReminderNotification(Context context, long reminderId) {
        createNotificationChannelStatic(context);

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Пора измерить давление")
                .setContentText("Откройте дневник и добавьте новое измерение.")
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(android.app.Notification.DEFAULT_SOUND | android.app.Notification.DEFAULT_VIBRATE);

        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (manager != null) {
            manager.notify((int) (1000 + reminderId % 100000), builder.build());
        }
    }

    private static class PressureChartView extends android.view.View {

        private final ArrayList<PressureRecord> records;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PressureChartView(Context context, ArrayList<PressureRecord> source) {
            super(context);
            records = new ArrayList<>(source);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int w = getWidth();
            int h = getHeight();

            canvas.drawColor(Color.rgb(17, 24, 39));

            paint.setColor(Color.rgb(148, 163, 184));
            paint.setTextSize(32);
            paint.setStrokeWidth(2);

            if (records.isEmpty()) {
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("График появится после записей", w / 2f, h / 2f, paint);
                return;
            }

            ArrayList<PressureRecord> data = new ArrayList<>();

            int limit = Math.min(records.size(), 14);

            for (int i = limit - 1; i >= 0; i--) {
                data.add(records.get(i));
            }

            int left = 70;
            int right = w - 30;
            int top = 45;
            int bottom = h - 55;

            int min = 60;
            int max = 180;

            for (PressureRecord r : data) {
                if (r.dia - 15 < min) min = r.dia - 15;
                if (r.sys + 15 > max) max = r.sys + 15;
            }

            if (min < 40) min = 40;
            if (max > 230) max = 230;

            paint.setColor(Color.rgb(51, 65, 85));
            paint.setStrokeWidth(2);

            for (int i = 0; i <= 4; i++) {
                float y = top + (bottom - top) * i / 4f;
                canvas.drawLine(left, y, right, y, paint);
            }

            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(24);
            paint.setColor(Color.rgb(148, 163, 184));

            for (int i = 0; i <= 4; i++) {
                int value = max - (max - min) * i / 4;
                float y = top + (bottom - top) * i / 4f;
                canvas.drawText(String.valueOf(value), left - 10, y + 8, paint);
            }

            drawLine(canvas, data, true, min, max, left, right, top, bottom);
            drawLine(canvas, data, false, min, max, left, right, top, bottom);

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(26);

            paint.setColor(Color.rgb(248, 113, 113));
            canvas.drawText("верхнее", left, 28, paint);

            paint.setColor(Color.rgb(56, 189, 248));
            canvas.drawText("нижнее", left + 130, 28, paint);
        }

        private void drawLine(
                Canvas canvas,
                ArrayList<PressureRecord> data,
                boolean systolic,
                int min,
                int max,
                int left,
                int right,
                int top,
                int bottom
        ) {
            if (data.isEmpty()) return;

            paint.setStrokeWidth(5);
            paint.setColor(systolic ? Color.rgb(248, 113, 113) : Color.rgb(56, 189, 248));

            float prevX = 0;
            float prevY = 0;

            for (int i = 0; i < data.size(); i++) {
                PressureRecord r = data.get(i);
                int value = systolic ? r.sys : r.dia;

                float x;

                if (data.size() == 1) {
                    x = (left + right) / 2f;
                } else {
                    x = left + (right - left) * i / (float) (data.size() - 1);
                }

                float y = bottom - (value - min) * (bottom - top) / (float) (max - min);

                if (i > 0) {
                    canvas.drawLine(prevX, prevY, x, y, paint);
                }

                canvas.drawCircle(x, y, 7, paint);

                prevX = x;
                prevY = y;
            }
        }
    }
}