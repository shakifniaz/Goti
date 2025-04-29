package com.example.goti;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class CustomerLoginActivity extends AppCompatActivity {
    private EditText mEmail, mPassword;
    private Button mLogin;
    private TextView mRegistration;
    private FirebaseAuth mAuth;
    private FirebaseAuth.AuthStateListener firebaseAuthListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            EdgeToEdge.enable(this);
            setContentView(R.layout.activity_customer_login);
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });

            mAuth = FirebaseAuth.getInstance();
            firebaseAuthListener = firebaseAuth -> {
                FirebaseUser user = mAuth.getCurrentUser();
                if (user != null) {
                    startActivity(new Intent(CustomerLoginActivity.this, CustomerMapActivity.class));
                    finish();
                }
            };

            mEmail = findViewById(R.id.email);
            mPassword = findViewById(R.id.password);
            mLogin = findViewById(R.id.loginButton);
            mRegistration = findViewById(R.id.registration);

            mRegistration.setOnClickListener(v -> {
                String email = mEmail.getText().toString();
                String password = mPassword.getText().toString();
                if (!email.isEmpty() && !password.isEmpty()) {
                    mAuth.createUserWithEmailAndPassword(email, password)
                            .addOnCompleteListener(this, task -> {
                                if (!task.isSuccessful()) {
                                    Toast.makeText(CustomerLoginActivity.this, "Sign up error", Toast.LENGTH_SHORT).show();
                                } else {
                                    String user_id = mAuth.getCurrentUser().getUid();
                                    DatabaseReference current_user_db = FirebaseDatabase.getInstance()
                                            .getReference()
                                            .child("Users")
                                            .child("Customers")
                                            .child(user_id);
                                    current_user_db.setValue(true);
                                }
                            });
                }
            });

            mLogin.setOnClickListener(v -> {
                String email = mEmail.getText().toString();
                String password = mPassword.getText().toString();
                if (!email.isEmpty() && !password.isEmpty()) {
                    mAuth.signInWithEmailAndPassword(email, password)
                            .addOnCompleteListener(this, task -> {
                                if (!task.isSuccessful()) {
                                    Toast.makeText(CustomerLoginActivity.this, "Sign in error", Toast.LENGTH_SHORT).show();
                                }
                            });
                }
            });
        } catch (Exception e) {
            Log.e("CustomerLogin", "Initialization error: " + e.getMessage());
            finish();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mAuth != null && firebaseAuthListener != null) {
            mAuth.addAuthStateListener(firebaseAuthListener);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mAuth != null && firebaseAuthListener != null) {
            mAuth.removeAuthStateListener(firebaseAuthListener);
        }
    }
}