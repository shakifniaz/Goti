package com.example.goti;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private Button mDriver, mCustomer;
    private TextView mLoginLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            // Initialize buttons
            mDriver = findViewById(R.id.driver);
            mCustomer = findViewById(R.id.customer);
            mLoginLink = findViewById(R.id.loginLink); // Make sure this matches XML ID

            // Set click listeners with error handling
            mDriver.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(MainActivity.this, DriverRegistrationActivity.class));
                } catch (Exception e) {
                    Log.e(TAG, "Driver registration error", e);
                    Toast.makeText(MainActivity.this, "Error opening driver registration", Toast.LENGTH_SHORT).show();
                }
            });

            mCustomer.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(MainActivity.this, CustomerRegistrationActivity.class));
                } catch (Exception e) {
                    Log.e(TAG, "Customer registration error", e);
                    Toast.makeText(MainActivity.this, "Error opening customer registration", Toast.LENGTH_SHORT).show();
                }
            });

            mLoginLink.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(MainActivity.this, LoginActivity.class));
                } catch (Exception e) {
                    Log.e(TAG, "Login error", e);
                    Toast.makeText(MainActivity.this, "Error opening login", Toast.LENGTH_SHORT).show();
                }
            });


        } catch (Exception e) {
            Log.e(TAG, "Activity initialization failed", e);
            Toast.makeText(this, "App initialization failed", Toast.LENGTH_LONG).show();
            finish();
        }
    }
}