package com.example.goti;

import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
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

    private static final String TAG = "DriverSettings";
    private static final int PICK_IMAGE_REQUEST = 1;

    private EditText mNameField, mPhoneField, mCarField;
    private Button mBack, mConfirm;
    private ImageView mProfileImage;
    private RadioGroup mRadioGroup;

    private FirebaseAuth mAuth;
    private DatabaseReference mDriverDatabase;
    private String userID;
    private Uri resultUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_settings);

        initializeViews();
        setupFirebase();
        getUserInfo();
        setupClickListeners();
    }

    private void initializeViews() {
        mNameField = findViewById(R.id.name);
        mPhoneField = findViewById(R.id.phone);
        mCarField = findViewById(R.id.car);
        mProfileImage = findViewById(R.id.profileImage);
        mBack = findViewById(R.id.back);
        mConfirm = findViewById(R.id.confirm);
        mRadioGroup = findViewById(R.id.radioGroup);
    }

    private void setupFirebase() {
        mAuth = FirebaseAuth.getInstance();
        userID = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid() : null;

        if (userID == null) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mDriverDatabase = FirebaseDatabase.getInstance().getReference()
                .child("Users")
                .child("Drivers")
                .child(userID);
    }

    private void setupClickListeners() {
        mProfileImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        });

        mConfirm.setOnClickListener(v -> {
            try {
                saveUserInformation();
            } catch (Exception e) {
                Log.e(TAG, "Error in saveUserInformation: " + e.getMessage(), e);
                Toast.makeText(DriverSettingsActivity.this,
                        "Error saving information", Toast.LENGTH_SHORT).show();
            }
        });

        mBack.setOnClickListener(v -> finish());
    }

    private void saveUserInformation() {
        Log.d(TAG, "Attempting to save user information");

        // Get field values
        String name = mNameField.getText().toString().trim();
        String phone = mPhoneField.getText().toString().trim();
        String car = mCarField.getText().toString().trim();

        // Validate service selection
        int selectedId = mRadioGroup.getCheckedRadioButtonId();
        if (selectedId == -1) {
            Toast.makeText(this, "Please select a service type", Toast.LENGTH_SHORT).show();
            return;
        }

        RadioButton selectedRadioButton = findViewById(selectedId);
        if (selectedRadioButton == null) {
            Toast.makeText(this, "Invalid service selection", Toast.LENGTH_SHORT).show();
            return;
        }

        String service = selectedRadioButton.getText().toString();
        Log.d(TAG, "Selected service: " + service);

        // Validate other fields
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
            mCarField.setError("Car is required");
            mCarField.requestFocus();
            return;
        }

        Log.d(TAG, "All fields validated successfully");

        if (resultUri != null) {
            Log.d(TAG, "Uploading profile image...");
            uploadProfileImage(name, phone, car, service);
        } else {
            Log.d(TAG, "Updating user data without image");
            updateUserData(name, phone, car, service, null);
        }
    }

    private void uploadProfileImage(String name, String phone, String car, String service) {
        if (userID == null) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
            return;
        }

        StorageReference filePath = FirebaseStorage.getInstance().getReference()
                .child("profile_images")
                .child(userID + ".jpg");

        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), resultUri);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            byte[] data = baos.toByteArray();

            ProgressDialog progressDialog = new ProgressDialog(this);
            progressDialog.setTitle("Saving Profile");
            progressDialog.setMessage("Please wait while we save your information...");
            progressDialog.setCancelable(false);
            progressDialog.show();

            UploadTask uploadTask = filePath.putBytes(data);
            uploadTask.addOnFailureListener(e -> {
                progressDialog.dismiss();
                Log.e(TAG, "Image upload failed: " + e.getMessage());
                Toast.makeText(DriverSettingsActivity.this,
                        "Failed to upload image", Toast.LENGTH_SHORT).show();
            }).addOnSuccessListener(taskSnapshot -> {
                filePath.getDownloadUrl().addOnSuccessListener(uri -> {
                    String downloadUrl = uri.toString();
                    Log.d(TAG, "Image uploaded successfully: " + downloadUrl);
                    updateUserData(name, phone, car, service, downloadUrl);
                    progressDialog.dismiss();
                }).addOnFailureListener(e -> {
                    progressDialog.dismiss();
                    Log.e(TAG, "Failed to get download URL: " + e.getMessage());
                    Toast.makeText(DriverSettingsActivity.this,
                            "Failed to get image URL", Toast.LENGTH_SHORT).show();
                });
            });
        } catch (IOException e) {
            Log.e(TAG, "Image processing error: " + e.getMessage());
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUserData(String name, String phone, String car, String service, String profileImageUrl) {
        if (userID == null) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("name", name);
        userInfo.put("phone", phone);
        userInfo.put("car", car);
        userInfo.put("service", service);

        if (profileImageUrl != null) {
            userInfo.put("profileImageUrl", profileImageUrl);
        }

        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Saving profile...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        mDriverDatabase.updateChildren(userInfo)
                .addOnCompleteListener(task -> {
                    progressDialog.dismiss();
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Profile updated successfully");
                        Toast.makeText(DriverSettingsActivity.this,
                                "Profile updated successfully",
                                Toast.LENGTH_SHORT).show();

                        // Navigate back to DriverMapActivity
                        Intent intent = new Intent(DriverSettingsActivity.this, DriverMapActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(intent);
                        finish();
                    } else {
                        Log.e(TAG, "Database update failed: " + task.getException().getMessage());
                        Toast.makeText(DriverSettingsActivity.this,
                                "Failed to update profile: " + task.getException().getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            resultUri = data.getData();
            try {
                mProfileImage.setImageURI(resultUri);
            } catch (Exception e) {
                Log.e(TAG, "Error setting image: " + e.getMessage());
                Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void getUserInfo() {
        if (mDriverDatabase == null) {
            Log.e(TAG, "Database reference is null");
            return;
        }

        mDriverDatabase.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    if (snapshot.hasChild("name")) {
                        mNameField.setText(snapshot.child("name").getValue(String.class));
                    }
                    if (snapshot.hasChild("phone")) {
                        mPhoneField.setText(snapshot.child("phone").getValue(String.class));
                    }
                    if (snapshot.hasChild("car")) {
                        mCarField.setText(snapshot.child("car").getValue(String.class));
                    }
                    if (snapshot.hasChild("service")) {
                        String service = snapshot.child("service").getValue(String.class);
                        if (service != null) {
                            switch (service) {
                                case "GotiX":
                                    mRadioGroup.check(R.id.GotiX);
                                    break;
                                case "GotiBlack":
                                    mRadioGroup.check(R.id.GotiBlack);
                                    break;
                                case "GotiXl":
                                    mRadioGroup.check(R.id.GotiXl);
                                    break;
                            }
                        }
                    }
                    if (snapshot.hasChild("profileImageUrl")) {
                        String imageUrl = snapshot.child("profileImageUrl").getValue(String.class);
                        if (imageUrl != null && !imageUrl.isEmpty()) {
                            Glide.with(DriverSettingsActivity.this)
                                    .load(imageUrl)
                                    .into(mProfileImage);
                        }
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Database error: " + error.getMessage());
                Toast.makeText(DriverSettingsActivity.this,
                        "Failed to load profile data",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}