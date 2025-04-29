package com.example.goti;

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

public class DriverRegistrationActivity extends AppCompatActivity {
    private EditText mEmail, mPassword;
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
