package com.example.goti;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.firebase.geofire.GeoFire;
import com.firebase.geofire.GeoLocation;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.List;

public class DriverMapActivity extends FragmentActivity implements OnMapReadyCallback {

    private static final String TAG = "DriverMapActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private DatabaseReference driverLocationRef;
    private DatabaseReference assignedCustomerRef;
    private GeoFire geoFire;
    private String userID;
    private Button mLogout, mSettings;
    private String customerID = "";
    private Marker pickupMarker, destinationMarker;
    private DatabaseReference assignedCustomerPickupLocationRef;
    private ValueEventListener assignedCustomerPickupLocationRefListener;
    private LinearLayout mCustomerInfo;
    private ImageView mCustomerProfileImage;
    private TextView mCustomerName, mCustomerPhone, mCustomerDestination;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_map);

        // Initialize all views first
        initializeViews();

        // Then setup Firebase
        setupFirebaseReferences();

        // Then setup map
        setupMap();

        // Then setup location services
        setupLocationClient();

        // Then setup listeners
        setupButtonListeners();
        setupCustomerAssignmentListener();

        // Initialize other variables
        customerID = "";
    }

    private void initializeViews() {
        mCustomerInfo = findViewById(R.id.customerInfo);
        mCustomerProfileImage = findViewById(R.id.customerProfileImage);
        mCustomerName = findViewById(R.id.customerName);
        mCustomerPhone = findViewById(R.id.customerPhone);
        mCustomerDestination = findViewById(R.id.customerDestination);
        mLogout = findViewById(R.id.logout);
        mSettings = findViewById(R.id.settings);
    }

    private void setupFirebaseReferences() {
        userID = FirebaseAuth.getInstance().getCurrentUser().getUid();
        if (userID == null) {
            Log.e(TAG, "User ID is null, user not authenticated");
            finish();
            return;
        }

        driverLocationRef = FirebaseDatabase.getInstance().getReference("driversAvailable");
        geoFire = new GeoFire(driverLocationRef);
    }

    private void setupMap() {
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else {
            Log.e(TAG, "Map fragment is null");
            Toast.makeText(this, "Error initializing map", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupLocationClient() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        requestLocationPermissions();
    }

    private void setupButtonListeners() {
        mLogout.setOnClickListener(v -> logoutUser());
        mSettings.setOnClickListener(v -> openSettings());
    }

    private void logoutUser() {
        cleanupDriverLocation();
        FirebaseAuth.getInstance().signOut();
        startActivity(new Intent(DriverMapActivity.this, MainActivity.class));
        finish();
    }

    private void openSettings() {
        startActivity(new Intent(DriverMapActivity.this, DriverSettingsActivity.class));
    }

    private void setupCustomerAssignmentListener() {
        if (userID == null) {
            Log.e(TAG, "User ID is null in setupCustomerAssignmentListener");
            return;
        }

        Log.d(TAG, "Setting up customer assignment listener for user: " + userID);

        assignedCustomerRef = FirebaseDatabase.getInstance()
                .getReference("Users/Drivers/" + userID + "/customerRequest/customerRideID");

        assignedCustomerRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                try {
                    Log.d(TAG, "Customer assignment data changed: " + dataSnapshot.toString());

                    if (dataSnapshot.exists()) {
                        customerID = dataSnapshot.getValue(String.class);
                        Log.d(TAG, "Customer ID: " + customerID);

                        if (customerID != null && !customerID.isEmpty()) {
                            showCustomerInfo();
                        } else {
                            clearCustomerInfo();
                        }
                    } else {
                        clearCustomerInfo();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in customer assignment listener", e);
                    clearCustomerInfo();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Error checking assignment", databaseError.toException());
                clearCustomerInfo();
            }
        });
    }

    private void showCustomerInfo() {
        try {
            mCustomerInfo.setVisibility(View.VISIBLE); // Show info panel first
            getAssignedCustomerPickupLocation();
            getAssignedCustomerInfo();
            getAssignedCustomerDestination();

            // Add this to ensure map is ready before adding markers
            if (mMap != null) {
                mMap.animateCamera(CameraUpdateFactory.zoomTo(15f));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing customer info", e);
            clearCustomerInfo();
        }
    }

    private void clearCustomerInfo() {
        try {
            customerID = "";
            removeAllMarkers();
            mCustomerInfo.setVisibility(View.GONE);
            resetCustomerInfoFields();
        } catch (Exception e) {
            Log.e(TAG, "Error clearing customer info", e);
        }
    }

    private void removeAllMarkers() {
        try {
            if (pickupMarker != null) {
                pickupMarker.remove();
                pickupMarker = null;
            }
            if (destinationMarker != null) {
                destinationMarker.remove();
                destinationMarker = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing markers", e);
        }
    }

    private void resetCustomerInfoFields() {
        mCustomerName.setText("");
        mCustomerPhone.setText("");
        mCustomerDestination.setText("Destination: --");
        mCustomerProfileImage.setImageResource(R.drawable.account_circle_24);
    }

    private void getAssignedCustomerPickupLocation() {
        if (customerID == null || customerID.isEmpty()) {
            Log.d(TAG, "Customer ID is null or empty");
            return;
        }

        // Remove previous listener if exists
        if (assignedCustomerPickupLocationRefListener != null && assignedCustomerPickupLocationRef != null) {
            assignedCustomerPickupLocationRef.removeEventListener(assignedCustomerPickupLocationRefListener);
        }

        assignedCustomerPickupLocationRef = FirebaseDatabase.getInstance()
                .getReference("CustomerRequest/" + customerID + "/l");

        assignedCustomerPickupLocationRefListener = assignedCustomerPickupLocationRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                try {
                    if (!snapshot.exists()) {
                        Log.d(TAG, "No pickup location data available");
                        return;
                    }

                    Object locationObj = snapshot.getValue();
                    if (locationObj instanceof List) {
                        List<?> location = (List<?>) locationObj;
                        if (location.size() >= 2) {
                            updatePickupMarker((List<Object>) location);
                        } else {
                            Log.d(TAG, "Invalid location data format");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing pickup location", e);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error getting pickup location", error.toException());
            }
        });
    }

    private void updatePickupMarker(List<Object> location) {
        try {
            double lat = Double.parseDouble(location.get(0).toString());
            double lng = Double.parseDouble(location.get(1).toString());
            LatLng pickupLatLng = new LatLng(lat, lng);

            if (pickupMarker != null) {
                pickupMarker.remove();
            }

            pickupMarker = mMap.addMarker(new MarkerOptions()
                    .position(pickupLatLng)
                    .title("Pickup Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));

            // Ensure map is not null before moving camera
            if (mMap != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pickupLatLng, 15));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating pickup marker", e);
        }
    }

    private void getAssignedCustomerInfo() {
        if (customerID == null || customerID.isEmpty()) return;

        DatabaseReference mCustomerDatabase = FirebaseDatabase.getInstance().getReference()
                .child("Users/Customers/" + customerID);

        mCustomerDatabase.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                try {
                    if (snapshot.exists()) {
                        if (snapshot.hasChild("name")) {
                            mCustomerName.setText(snapshot.child("name").getValue(String.class));
                        }
                        if (snapshot.hasChild("phone")) {
                            mCustomerPhone.setText(snapshot.child("phone").getValue(String.class));
                        }
                        if (snapshot.hasChild("profileImageUrl")) {
                            Glide.with(DriverMapActivity.this)
                                    .load(snapshot.child("profileImageUrl").getValue(String.class))
                                    .into(mCustomerProfileImage);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error getting customer info", e);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error getting customer info", error.toException());
            }
        });
    }

    private void getAssignedCustomerDestination() {
        if (customerID == null || customerID.isEmpty()) return;

        DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                .getReference("CustomerRequest/" + customerID);

        customerRequestRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                try {
                    if (dataSnapshot.exists()) {
                        String destinationName = dataSnapshot.child("destinationName").getValue(String.class);
                        if (destinationName != null) {
                            mCustomerDestination.setText("Destination: " + destinationName);
                        }

                        List<Object> latLng = (List<Object>) dataSnapshot.child("destinationLatLng").getValue();
                        if (latLng != null && latLng.size() >= 2) {
                            updateDestinationMarker(latLng, destinationName);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error getting destination", e);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Error getting destination", databaseError.toException());
            }
        });
    }

    private void updateDestinationMarker(List<Object> latLng, String destinationName) {
        try {
            double lat = Double.parseDouble(latLng.get(0).toString());
            double lng = Double.parseDouble(latLng.get(1).toString());
            LatLng destination = new LatLng(lat, lng);

            if (destinationMarker != null) {
                destinationMarker.remove();
            }

            destinationMarker = mMap.addMarker(new MarkerOptions()
                    .position(destination)
                    .title("Destination: " + (destinationName != null ? destinationName : "Unknown"))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
        } catch (Exception e) {
            Log.e(TAG, "Error updating destination marker", e);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation();
        } else {
            requestLocationPermissions();
        }
    }

    private void enableMyLocation() {
        try {
            if (mMap != null) {
                mMap.setMyLocationEnabled(true);
                setupLocationUpdates();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
            requestLocationPermissions();
        }
    }

    private void setupLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(5000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        updateDriverLocation(location);
                    }
                }
            }
        };

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, null);
            } else {
                requestLocationPermissions();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up location updates", e);
        }
    }

    private void updateDriverLocation(Location location) {
        if (location == null || userID == null || userID.isEmpty()) {
            Log.d(TAG, "Location or userID is null");
            return;
        }

        try {
            DatabaseReference refAvailable = FirebaseDatabase.getInstance().getReference("driversAvailable");
            DatabaseReference refWorking = FirebaseDatabase.getInstance().getReference("driversWorking");
            GeoFire geoFireAvailable = new GeoFire(refAvailable);
            GeoFire geoFireWorking = new GeoFire(refWorking);

            if (customerID == null || customerID.isEmpty()) {
                // Driver is available
                geoFireWorking.removeLocation(userID, (key, error) -> {
                    if (error != null) {
                        Log.e(TAG, "Error removing from working: " + error.getMessage());
                    }
                });

                geoFireAvailable.setLocation(userID,
                        new GeoLocation(location.getLatitude(), location.getLongitude()),
                        (key, error) -> {
                            if (error != null) {
                                Log.e(TAG, "Error updating available location: " + error.getMessage());
                            }
                        });
            } else {
                // Driver is working (has a customer)
                geoFireAvailable.removeLocation(userID, (key, error) -> {
                    if (error != null) {
                        Log.e(TAG, "Error removing from available: " + error.getMessage());
                    }
                });

                geoFireWorking.setLocation(userID,
                        new GeoLocation(location.getLatitude(), location.getLongitude()),
                        (key, error) -> {
                            if (error != null) {
                                Log.e(TAG, "Error updating working location: " + error.getMessage());
                            }
                        });
            }

            // Update the map camera to follow the driver
            if (mMap != null) {
                LatLng driverLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                mMap.animateCamera(CameraUpdateFactory.newLatLng(driverLatLng));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating driver location", e);
        }
    }

    private void requestLocationPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation();
            } else {
                Toast.makeText(this, "Location permission is required for this app to function properly", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void cleanupDriverLocation() {
        if (userID == null) return;

        try {
            DatabaseReference refAvailable = FirebaseDatabase.getInstance().getReference("driversAvailable");
            DatabaseReference refWorking = FirebaseDatabase.getInstance().getReference("driversWorking");
            GeoFire geoFireAvailable = new GeoFire(refAvailable);
            GeoFire geoFireWorking = new GeoFire(refWorking);

            geoFireAvailable.removeLocation(userID);
            geoFireWorking.removeLocation(userID);
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up driver location", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupDriverLocation();
        if (fusedLocationProviderClient != null && locationCallback != null) {
            try {
                fusedLocationProviderClient.removeLocationUpdates(locationCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error removing location updates", e);
            }
        }
        if (assignedCustomerPickupLocationRefListener != null && assignedCustomerPickupLocationRef != null) {
            assignedCustomerPickupLocationRef.removeEventListener(assignedCustomerPickupLocationRefListener);
        }
    }
}