package com.example.goti;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class DriverRegistrationActivity extends AppCompatActivity {
    private EditText mEmail, mPassword, mPasswordConfirm, mName, mDob, mPhone, mNid, mLicense;
    private RadioGroup mGenderGroup;
    private Button mRegister;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_registration);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // Initialize all views
        mEmail = findViewById(R.id.email);
        mPassword = findViewById(R.id.password);
        mPasswordConfirm = findViewById(R.id.password_confirm);
        mName = findViewById(R.id.name);
        mDob = findViewById(R.id.dob);
        mPhone = findViewById(R.id.phone);
        mNid = findViewById(R.id.nid_driver);
        mLicense = findViewById(R.id.licence_driver);
        mGenderGroup = findViewById(R.id.genderGroup);
        mRegister = findViewById(R.id.registerButton);

        // Date picker for DOB
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
            // Get all input values
            String email = mEmail.getText().toString().trim();
            String password = mPassword.getText().toString().trim();
            String confirmPassword = mPasswordConfirm.getText().toString().trim();
            String name = mName.getText().toString().trim();
            String dob = mDob.getText().toString().trim();
            String phone = mPhone.getText().toString().trim();
            String nid = mNid.getText().toString().trim();
            String license = mLicense.getText().toString().trim();

            // Get selected gender
            int selectedGenderId = mGenderGroup.getCheckedRadioButtonId();
            RadioButton selectedGender = findViewById(selectedGenderId);
            String gender = selectedGender != null ? selectedGender.getText().toString() : "";

            // Validate all fields
            if (email.isEmpty()) {
                mEmail.setError("Email is required");
                mEmail.requestFocus();
                return;
            }

            if (password.isEmpty()) {
                mPassword.setError("Password is required");
                mPassword.requestFocus();
                return;
            }

            if (!password.equals(confirmPassword)) {
                mPasswordConfirm.setError("Passwords do not match");
                mPasswordConfirm.requestFocus();
                return;
            }

            if (name.isEmpty()) {
                mName.setError("Name is required");
                mName.requestFocus();
                return;
            }

            if (dob.isEmpty()) {
                mDob.setError("Date of birth is required");
                mDob.requestFocus();
                return;
            }

            if (phone.isEmpty()) {
                mPhone.setError("Phone number is required");
                mPhone.requestFocus();
                return;
            }

            if (nid.isEmpty()) {
                mNid.setError("NID number is required");
                mNid.requestFocus();
                return;
            }

            if (license.isEmpty()) {
                mLicense.setError("License number is required");
                mLicense.requestFocus();
                return;
            }

            if (selectedGenderId == -1) {
                Toast.makeText(DriverRegistrationActivity.this, "Please select gender", Toast.LENGTH_SHORT).show();
                return;
            }

            // Create user with email and password
            mAuth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        if (task.isSuccessful()) {
                            String user_id = mAuth.getCurrentUser().getUid();
                            DatabaseReference current_user_db = mDatabase.child("Users").child("Drivers").child(user_id);

                            // Create user data map
                            Map<String, Object> userMap = new HashMap<>();
                            userMap.put("email", email);
                            userMap.put("name", name);
                            userMap.put("phone", phone);
                            userMap.put("dob", dob);
                            userMap.put("gender", gender);
                            userMap.put("nid", nid);
                            userMap.put("license", license);

                            // Save user data to database
                            current_user_db.updateChildren(userMap)
                                    .addOnCompleteListener(dbTask -> {
                                        if (dbTask.isSuccessful()) {
                                            Toast.makeText(DriverRegistrationActivity.this, "Registration successful", Toast.LENGTH_SHORT).show();
                                            startActivity(new Intent(DriverRegistrationActivity.this, DriverMapActivity.class));
                                            finish();
                                        } else {
                                            Toast.makeText(DriverRegistrationActivity.this, "Failed to save user data", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                        } else {
                            Toast.makeText(DriverRegistrationActivity.this, "Registration error: " + task.getException().getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
        });

        TextView loginLink = findViewById(R.id.login);
        loginLink.setOnClickListener(v -> {
            startActivity(new Intent(DriverRegistrationActivity.this, LoginActivity.class));
            finish();
        });
    }
}