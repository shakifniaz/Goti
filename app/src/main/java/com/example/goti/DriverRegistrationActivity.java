package com.example.goti;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.Calendar;
import java.util.Locale;

public class DriverRegistrationActivity extends AppCompatActivity {
    private EditText mEmail, mPassword,mDob;
    private Button mRegister;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_registration);

        mAuth = FirebaseAuth.getInstance();

        mEmail = findViewById(R.id.email);
        mPassword = findViewById(R.id.password);
        mRegister = findViewById(R.id.registerButton);

        mDob = findViewById(R.id.dob);
        mDob.setOnClickListener(v -> {
            final Calendar calendar = Calendar.getInstance();
            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH);
            int day = calendar.get(Calendar.DAY_OF_MONTH);

            DatePickerDialog datePickerDialog = new DatePickerDialog(
                    DriverRegistrationActivity.this,
                    (view, year1, monthOfYear, dayOfMonth) -> {
                        String selectedDate = String.format(Locale.getDefault(), "%02d/%02d/%04d", dayOfMonth, monthOfYear + 1, year1);
                        mDob.setText(selectedDate);
                    },
                    year, month, day);

            datePickerDialog.show();
        });

        mRegister.setOnClickListener(v -> {
            String email = mEmail.getText().toString();
            String password = mPassword.getText().toString();

            if (!email.isEmpty() && !password.isEmpty()) {
                mAuth.createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener(this, task -> {
                            if (task.isSuccessful()) {
                                String user_id = mAuth.getCurrentUser().getUid();
                                DatabaseReference current_user_db = FirebaseDatabase.getInstance()
                                        .getReference()
                                        .child("Users")
                                        .child("Drivers")
                                        .child(user_id);
                                current_user_db.setValue(true);

                                startActivity(new Intent(DriverRegistrationActivity.this, DriverMapActivity.class));
                                finish();
                            } else {
                                Toast.makeText(DriverRegistrationActivity.this, "Registration error", Toast.LENGTH_SHORT).show();
                            }
                        });
            } else {
                Toast.makeText(DriverRegistrationActivity.this, "Email and password cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });

        TextView loginLink = findViewById(R.id.login);
        loginLink.setOnClickListener(v -> {
            startActivity(new Intent(DriverRegistrationActivity.this, LoginActivity.class));
            finish();
        });
    }
}
