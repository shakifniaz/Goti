package com.example.goti;

import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class DriverSettingsActivity extends AppCompatActivity {

    private EditText mNameField, mPhoneField, nCarField;
    private Button mBack, mConfirm;
    private ImageView mProfileImage;
    private FirebaseAuth mAuth;
    private DatabaseReference mDriverrDatabase;
    private String userID;
    private Uri resultUri;
    private static final int PICK_IMAGE_REQUEST = 1;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_driver_settings);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize views
        mNameField = findViewById(R.id.name);
        mPhoneField = findViewById(R.id.phone);
        nCarField = findViewById(R.id.car);
        mProfileImage = findViewById(R.id.profileImage);
        mBack = findViewById(R.id.back);
        mConfirm = findViewById(R.id.confirm);

        // Initialize Firebase
        mAuth = FirebaseAuth.getInstance();
        userID = mAuth.getCurrentUser().getUid();
        mDriverrDatabase = FirebaseDatabase.getInstance().getReference()
                .child("Users")
                .child("Drivers")
                .child(userID);

        // Load user info
        getUserInfo();

        mProfileImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        });

        // Set click listeners
        mConfirm.setOnClickListener(v -> saveUserInformation());
        mBack.setOnClickListener(v -> finish());
    }

    private void getUserInfo() {
        mDriverrDatabase.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    // Get name if exists
                    if (snapshot.hasChild("name")) {
                        String name = snapshot.child("name").getValue(String.class);
                        mNameField.setText(name);
                    }
                    // Get phone if exists
                    if (snapshot.hasChild("phone")) {
                        String phone = snapshot.child("phone").getValue(String.class);
                        mPhoneField.setText(phone);
                    }
                    // Get car if exists
                    if (snapshot.hasChild("car")) {
                        String car = snapshot.child("car").getValue(String.class);
                        nCarField.setText(car);
                    }
                    // Get profile image if exists
                    if (snapshot.hasChild("profileImageUrl")) {
                        String profileImageUrl = snapshot.child("profileImageUrl").getValue(String.class);
                        Glide.with(DriverSettingsActivity.this)
                                .load(profileImageUrl)
                                .into(mProfileImage);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(DriverSettingsActivity.this,
                        "Failed to load user data: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveUserInformation() {
        String name = mNameField.getText().toString().trim();
        String phone = mPhoneField.getText().toString().trim();
        String car = nCarField.getText().toString().trim();

        // Validate inputs
        if (name.isEmpty()) {
            mNameField.setError("Name is required");
            mNameField.requestFocus();
            return;
        }

        if (phone.isEmpty()) {
            mPhoneField.setError("Phone number is required");
            mPhoneField.requestFocus();
            return;
        }

        if (car.isEmpty()) {
            nCarField.setError("Car is required");
            nCarField.requestFocus();
            return;
        }

        if (resultUri != null) {
            // If new image is selected, upload it first
            uploadProfileImage(name, phone, car);
        } else {
            // If no new image, just update text data
            updateUserData(name, phone, car,null);
        }
    }

    private void uploadProfileImage(String name, String phone, String car) {
        // Get reference to Firebase Storage
        StorageReference filePath = FirebaseStorage.getInstance().getReference()
                .child("profile_images")
                .child(userID);

        try {
            // Compress the image
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), resultUri);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 20, baos);
            byte[] data = baos.toByteArray();

            // Show loading dialog
            ProgressDialog progressDialog = new ProgressDialog(this);
            progressDialog.setMessage("Uploading profile image...");
            progressDialog.setCancelable(false);
            progressDialog.show();

            // Upload the image
            UploadTask uploadTask = filePath.putBytes(data);

            uploadTask.continueWithTask(task -> {
                if (!task.isSuccessful()) {
                    progressDialog.dismiss();
                    throw task.getException();
                }
                return filePath.getDownloadUrl();
            }).addOnCompleteListener(task -> {
                progressDialog.dismiss();
                if (task.isSuccessful()) {
                    Uri downloadUri = task.getResult();
                    updateUserData(name, phone, car, downloadUri.toString());
                } else {
                    Toast.makeText(DriverSettingsActivity.this,
                            "Upload failed: " + task.getException().getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        } catch (IOException e) {
            Toast.makeText(this, "Error processing image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUserData(String name, String phone, String car, String profileImageUrl) {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("name", name);
        userInfo.put("phone", phone);
        userInfo.put("car", car);

        if (profileImageUrl != null) {
            userInfo.put("profileImageUrl", profileImageUrl);
        }

        // Show loading dialog
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Saving profile...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        mDriverrDatabase.updateChildren(userInfo)
                .addOnCompleteListener(task -> {
                    progressDialog.dismiss();
                    if (task.isSuccessful()) {
                        Toast.makeText(DriverSettingsActivity.this,
                                "Profile updated successfully",
                                Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(DriverSettingsActivity.this,
                                "Database error: " + task.getException().getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            resultUri = data.getData();
            mProfileImage.setImageURI(resultUri);
        }
    }
}