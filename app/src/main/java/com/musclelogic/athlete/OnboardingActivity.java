package com.musclelogic.athlete;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.musclelogic.athlete.capture.SessionCaptureActivity;

public class OnboardingActivity extends AppCompatActivity {

    private static final String PREFS = "app_prefs";
    private static final String KEY_ONBOARDED = "onboarded";

    /** Returns true if onboarding has already been shown. */
    public static boolean isOnboarded(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                  .getBoolean(KEY_ONBOARDED, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        Button btnDemo    = findViewById(R.id.btnOnboardingDemo);
        Button btnGotIt   = findViewById(R.id.btnOnboardingGotIt);
        Button btnPrivacy = findViewById(R.id.btnOnboardingPrivacy);

        btnGotIt.setOnClickListener(v -> finish());

        btnPrivacy.setOnClickListener(v ->
                startActivity(new Intent(this, PrivacyPolicyActivity.class)));

        btnDemo.setOnClickListener(v -> {
            markOnboarded();
            Intent i = new Intent(this, SessionCaptureActivity.class);
            i.putExtra("DEMO_MODE", true);
            startActivity(i);
            finish();
        });
    }

    @Override
    public void finish() {
        markOnboarded();
        super.finish();
    }

    private void markOnboarded() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_ONBOARDED, true).apply();
    }
}
