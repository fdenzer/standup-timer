package net.johnpwood.android.standuptimer;

import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

public class StandupTimer extends Activity implements OnClickListener {
    protected static final String REMAINING_INDIVIDUAL_SECONDS = "remainingIndividualSeconds";
    protected static final String REMAINING_MEETING_SECONDS = "remainingMeetingSeconds";
    protected static final String STARTING_INDIVIDUAL_SECONDS = "startingIndividualSeconds";
    protected static final String COMPLETED_PARTICIPANTS = "completedParticipants";
    protected static final String TOTAL_PARTICIPANTS = "totalParticipants";

    private int remainingIndividualSeconds = 0;
    private int remainingMeetingSeconds = 0;
    private int startingIndividualSeconds = 0;
    private int completedParticipants = 0;
    private int totalParticipants = 0;
    private int warningTime = 0;

    private boolean finished = false;
    private Timer timer = null;
    private PowerManager.WakeLock wakeLock = null;

    private static MediaPlayer bell = null;
    private static MediaPlayer airhorn = null;

    private Handler updateDisplayHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            updateDisplay();
        }
    };

    private Handler disableIndividualTimerHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            disableIndividualTimer();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.timer);

        initializeSounds();
        initializeButtonListeners();
        initializeTimerValues();
        updateDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        acquireWakeLock();
        startTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();

        synchronized(this) {
            cancelTimer();
            releaseWakeLock();

            if (finished) {
                clearState();
            } else {
                saveState();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent keyEvent) {
        Logger.i("Key pressed: " + keyCode);
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            shutdownTimer();
        }
        return super.onKeyDown(keyCode, keyEvent);
    }

    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.next_button:
            processNextButtonClick();
            break;

        case R.id.finished_button:
            processFinishedButtonClick();
            break;
        }
    }

    private void initializeSounds() {
        if (bell == null) {
            Logger.d("Loading the bell sound");
            bell = MediaPlayer.create(this, R.raw.bell);
        }

        if (airhorn == null) {
            Logger.d("Loading the airhorn sound");
            airhorn = MediaPlayer.create(this, R.raw.airhorn);
        }
    }

    private void initializeButtonListeners() {
        View nextButton = findViewById(R.id.next_button);
        nextButton.setOnClickListener(this);

        View finishedButton = findViewById(R.id.finished_button);
        finishedButton.setOnClickListener(this);
    }

    private void initializeTimerValues() {
        int meetingLengthPos = getIntent().getIntExtra("meetingLengthPos", 0);
        int numParticipants = getIntent().getIntExtra("numParticipants", 0);

        Logger.d("Data from Intent: meetingLengthPos = " + meetingLengthPos);
        Logger.d("Data from Intent: numParticipants = " + numParticipants);

        loadState(meetingLengthPos, numParticipants);
    }

    protected synchronized void updateDisplay() {
        if (individualStatusInProgress()) {
            TextView individualTimeRemaining = (TextView) findViewById(R.id.individual_time_remaining);
            individualTimeRemaining.setText(formatTime(remainingIndividualSeconds));
            individualTimeRemaining.setTextColor(determineColor(remainingIndividualSeconds, warningTime));

            TextView participantNumber = (TextView) findViewById(R.id.participant_number);
            participantNumber.setText(this.getString(R.string.participant) + " " +
                    (completedParticipants + 1) + "/" + totalParticipants);
        } else {
            disableIndividualTimer();
        }

        TextView totalTimeRemaining = (TextView) findViewById(R.id.total_time_remaining);
        totalTimeRemaining.setText(formatTime(remainingMeetingSeconds));
        totalTimeRemaining.setTextColor(determineColor(remainingMeetingSeconds, warningTime));
    }

    private synchronized void startTimer() {
        Logger.d("Starting a new timer");

        timer = new Timer();
        TimerTask updateTimerValuesTask = new TimerTask() {
            @Override
            public void run() {
                updateTimerValues();
            }
        };
        timer.schedule(updateTimerValuesTask, 1000, 1000);
    }

    private synchronized void cancelTimer() {
        if (timer != null) {
            Logger.d("Canceling timer");
            timer.cancel();
            timer = null;
        }
    }

    protected synchronized void updateTimerValues() {
        if (remainingIndividualSeconds > 0) {
            remainingIndividualSeconds--;

            if (remainingIndividualSeconds == warningTime) {
                Logger.d("Playing the bell sound");
                if (Prefs.playSounds(this)) {
                    playWarningSound();
                }
            } else if (remainingIndividualSeconds == 0) {
                Logger.d("Playing the airhorn sound");
                if (Prefs.playSounds(this)) {
                    playFinishedSound();
                }
            }
        }

        if (remainingMeetingSeconds > 0)
            remainingMeetingSeconds--;

        updateDisplayHandler.sendEmptyMessage(0);
    }

    private synchronized void processNextButtonClick() {
        completedParticipants++;

        if (completedParticipants == totalParticipants) {
            disableIndividualTimerHandler.sendEmptyMessage(0);
        } else {
            if (startingIndividualSeconds < remainingMeetingSeconds) {
                remainingIndividualSeconds = startingIndividualSeconds;
            } else {
                remainingIndividualSeconds = remainingMeetingSeconds;
            }
            updateDisplay();
        }
    }

    private synchronized void disableIndividualTimer() {
        remainingIndividualSeconds = 0;

        TextView participantNumber = (TextView) findViewById(R.id.participant_number);
        participantNumber.setText(R.string.individual_status_complete);

        TextView individualTimeRemaining = (TextView) findViewById(R.id.individual_time_remaining);
        individualTimeRemaining.setText(formatTime(remainingIndividualSeconds));
        individualTimeRemaining.setTextColor(Color.GRAY);

        Button nextButton = (Button) findViewById(R.id.next_button);
        nextButton.setClickable(false);
        nextButton.setTextColor(Color.GRAY);
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            Logger.d("Acquiring wake lock");
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, this.getClass().getCanonicalName());
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            Logger.d("Releasing wake lock");
            wakeLock.release();
            wakeLock = null;
        }
    }

    protected synchronized void loadState(int meetingLengthPos, int numParticipants) {
        warningTime = Prefs.getWarningTime(this);

        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        totalParticipants = preferences.getInt(TOTAL_PARTICIPANTS, numParticipants);
        remainingMeetingSeconds = preferences.getInt(REMAINING_MEETING_SECONDS, getMeetingLength(meetingLengthPos));
        startingIndividualSeconds = preferences.getInt(STARTING_INDIVIDUAL_SECONDS, remainingMeetingSeconds / totalParticipants);
        remainingIndividualSeconds = preferences.getInt(REMAINING_INDIVIDUAL_SECONDS, startingIndividualSeconds);
        completedParticipants = preferences.getInt(COMPLETED_PARTICIPANTS, 0);
    }

    private synchronized void saveState() {
        SharedPreferences.Editor preferences = getPreferences(MODE_PRIVATE).edit();
        preferences.putInt(REMAINING_INDIVIDUAL_SECONDS, remainingIndividualSeconds);
        preferences.putInt(REMAINING_MEETING_SECONDS, remainingMeetingSeconds);
        preferences.putInt(STARTING_INDIVIDUAL_SECONDS, startingIndividualSeconds);
        preferences.putInt(COMPLETED_PARTICIPANTS, completedParticipants);
        preferences.putInt(TOTAL_PARTICIPANTS, totalParticipants);
        preferences.commit();
    }

    private void clearState() {
        getPreferences(MODE_PRIVATE).edit().clear().commit();
    }

    private synchronized boolean individualStatusInProgress() {
        return completedParticipants < totalParticipants;
    }

    private synchronized void processFinishedButtonClick() {
        shutdownTimer();
        finish();
    }

    private synchronized void shutdownTimer() {
        destroySounds();
        finished = true;
    }

    private static void destroySounds() {
        bell.stop();
        bell.release();
        bell = null;

        airhorn.stop();
        airhorn.release();
        airhorn = null;
    }

    protected static String formatTime(int seconds) {
        return Integer.toString(seconds / 60) + ":" + padWithZeros(seconds % 60);
    }

    private static String padWithZeros(int seconds) {
        return seconds < 10 ? "0" + seconds : Integer.toString(seconds);
    }

    private static int determineColor(int seconds, int warningTime) {
        if (seconds == 0) {
            return Color.RED;
        } else if (seconds <= warningTime) {
            return Color.YELLOW;
        } else {
            return Color.GREEN;
        }
    }

    protected void playWarningSound() {
        playSound(bell);
    }

    protected void playFinishedSound() {
        playSound(airhorn);
    }

    private void playSound(MediaPlayer mp) {
        mp.seekTo(0);
        mp.start();
    }

    private static int getMeetingLength(int meetingLengthPos) {
        int minutes = 0;
        switch (meetingLengthPos) {
            case 0: minutes = 5;  break;
            case 1: minutes = 10; break;
            case 2: minutes = 15; break;
            case 3: minutes = 20; break;
        }
        return minutes * 60;
    }

    protected synchronized int getRemainingIndividualSeconds() {
        return remainingIndividualSeconds;
    }

    protected synchronized int getRemainingMeetingSeconds() {
        return remainingMeetingSeconds;
    }

    protected synchronized int getStartingIndividualSeconds() {
        return startingIndividualSeconds;
    }

    protected synchronized int getCompletedParticipants() {
        return completedParticipants;
    }

    protected synchronized int getTotalParticipants() {
        return totalParticipants;
    }

    protected synchronized int getWarningTime() {
        return warningTime;
    }

    protected synchronized boolean isFinished() {
        return finished;
    }

    protected synchronized Timer getTimer() {
        return timer;
    }

    protected synchronized PowerManager.WakeLock getWakeLock() {
        return wakeLock;
    }
}