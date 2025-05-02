package com.example.goti;

import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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
import com.google.android.gms.tasks.OnCompleteListener;
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

        // Initialize views
        mNameField = findViewById(R.id.name);
        mPhoneField = findViewById(R.id.phone);
        mCarField = findViewById(R.id.car);
        mProfileImage = findViewById(R.id.profileImage);
        mBack = findViewById(R.id.back);
        mConfirm = findViewById(R.id.confirm);
        mRadioGroup = findViewById(R.id.radioGroup);

        // Initialize Firebase
        mAuth = FirebaseAuth.getInstance();
        userID = mAuth.getCurrentUser().getUid();
        mDriverDatabase = FirebaseDatabase.getInstance().getReference()
                .child("Users")
                .child("Drivers")
                .child(userID);

        // Load user info
        getUserInfo();

        // Set click listeners
        mProfileImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        });

        mConfirm.setOnClickListener(v -> saveUserInformation());
        mBack.setOnClickListener(v -> finish());
    }

    private void getUserInfo() {
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
                                case "CNG":
                                    mRadioGroup.check(R.id.GotiX);
                                    break;
                                case "BIKE":
                                    mRadioGroup.check(R.id.GotiBlack);
                                    break;
                                case "CAR":
                                    mRadioGroup.check(R.id.GotiXl);
                                    break;
                            }
                        }
                    }
                    if (snapshot.hasChild("profileImageUrl")) {
                        String imageUrl = snapshot.child("profileImageUrl").getValue(String.class);
                        Glide.with(DriverSettingsActivity.this)
                                .load(imageUrl)
                                .into(mProfileImage);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(DriverSettingsActivity.this,
                        "Failed to load profile data",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveUserInformation() {
        String name = mNameField.getText().toString().trim();
        String phone = mPhoneField.getText().toString().trim();
        String car = mCarField.getText().toString().trim();

        // Get selected service type
        int selectedId = mRadioGroup.getCheckedRadioButtonId();
        if (selectedId == -1) {
            Toast.makeText(this, "Please select a service type", Toast.LENGTH_SHORT).show();
            return;
        }
        RadioButton selectedRadioButton = findViewById(selectedId);
        String service = selectedRadioButton.getText().toString();

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
            mCarField.setError("Car information is required");
            mCarField.requestFocus();
            return;
        }

        if (resultUri != null) {
            uploadProfileImage(name, phone, car, service);
        } else {
            updateUserData(name, phone, car, service, null);
        }
    }

    private void uploadProfileImage(String name, String phone, String car, String service) {
        StorageReference filePath = FirebaseStorage.getInstance().getReference()
                .child("profile_images")
                .child(userID);

        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), resultUri);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 20, baos);
            byte[] data = baos.toByteArray();

            ProgressDialog progressDialog = new ProgressDialog(this);
            progressDialog.setMessage("Uploading profile image...");
            progressDialog.setCancelable(false);
            progressDialog.show();

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
                    updateUserData(name, phone, car, service, downloadUri.toString());
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

    private void updateUserData(String name, String phone, String car, String service, String profileImageUrl) {
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