package com.example.basilience;

import android.app.Application;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        try {
            FirebaseOptions options = FirebaseApp.getInstance().getOptions();
            FirebaseApp.initializeApp(this, options, "secondary");
        } catch (IllegalStateException ignore) {
            // already initialized
        }
    }
}