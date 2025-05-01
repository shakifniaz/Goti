package com.example.goti;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.libraries.places.api.Places;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.maps.DirectionsApi;
import com.google.maps.GeoApiContext;
import com.google.maps.model.DirectionsResult;
import com.google.maps.model.TravelMode;
import com.google.maps.android.PolyUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private Button mLogout, mSettings, mRideStatus;
    private int status = 0;
    private String customerID = "", destination;
    private LatLng destinationLatLng;
    private Marker pickupMarker, destinationMarker;
    private DatabaseReference assignedCustomerPickupLocationRef;
    private ValueEventListener assignedCustomerPickupLocationRefListener;
    private LinearLayout mCustomerInfo;
    private ImageView mCustomerProfileImage;
    private TextView mCustomerName, mCustomerPhone, mCustomerDestination;
    private Location mLastLocation;
    private List<Polyline> polylines = new ArrayList<>();
    GeoApiContext context = new GeoApiContext.Builder()
            .apiKey("AIzaSyBoz_AvnAD8F8AS32u7k3tKas-lxqoXp1Q")
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_map);

        if (!Places.isInitialized()) {
            Places.initialize(getApplicationContext(), "AIzaSyBoz_AvnAD8F8AS32u7k3tKas-lxqoXp1Q");
        }

        polylines = new ArrayList<>();
        mRideStatus = findViewById(R.id.rideStatus);
        mRideStatus.setOnClickListener(v -> {
            switch(status){
                case 0: // Available - no action
                    break;
                case 1: // Picked up customer
                    status = 2;
                    erasePolylines();
                    if (destinationLatLng != null) {
                        getRouteToMarker(destinationLatLng);
                    } else {
                        getAssignedCustomerDestination();
                    }
                    mRideStatus.setText("Complete Ride");
                    updateRideStatus("enroute");
                    break;
                case 2: // Completed ride
                    recordRideCompletion();
                    mRideStatus.setText("Available");
                    status = 0;
                    clearCustomerInfo();
                    updateRideStatus("completed");
                    break;
                default: // Add cancel option
                    cancelRideFromDriver();
                    break;
            }
        });

        initializeViews();
        setupFirebaseReferences();
        setupMap();
        setupLocationClient();
        setupButtonListeners();
        setupCustomerAssignmentListener();

        customerID = "";
    }

    private void updateRideStatus(String status) {
        if (customerID == null || customerID.isEmpty()) return;

        DatabaseReference rideRef = FirebaseDatabase.getInstance()
                .getReference("CustomerRequest/" + customerID + "/status");
        rideRef.setValue(status);
    }

    // In DriverMapActivity.java, update the recordRideCompletion method:
    private void recordRideCompletion() {
        if (userID == null || customerID == null) {
            Log.e(TAG, "userID or customerID is null - cannot record ride");
            return;
        }

        Log.d(TAG, "Recording ride completion for customer: " + customerID);

        // 1. First, update the ride status to "completed" in the customer's request
        DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                .getReference("CustomerRequest/" + customerID);

        Map<String, Object> updateMap = new HashMap<>();
        updateMap.put("status", "completed");

        customerRequestRef.updateChildren(updateMap)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Customer request status updated to completed");

                    // 2. Then proceed with recording ride history
                    DatabaseReference driverRef = FirebaseDatabase.getInstance()
                            .getReference("Users/Drivers/" + userID);
                    DatabaseReference customerRef = FirebaseDatabase.getInstance()
                            .getReference("Users/Customers/" + customerID);

                    driverRef.addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot driverSnapshot) {
                            customerRef.addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(@NonNull DataSnapshot customerSnapshot) {
                                    double distance = calculateRideDistance();
                                    double fare = calculateFare(distance);

                                    DatabaseReference historyRef = FirebaseDatabase.getInstance()
                                            .getReference("RideHistory").push();

                                    Map<String, Object> rideMap = new HashMap<>();
                                    rideMap.put("driverId", userID);
                                    rideMap.put("customerId", customerID);
                                    rideMap.put("driverName", driverSnapshot.child("name").getValue(String.class));
                                    rideMap.put("customerName", customerSnapshot.child("name").getValue(String.class));
                                    rideMap.put("carType", driverSnapshot.child("car").getValue(String.class));
                                    rideMap.put("destination", destination);
                                    rideMap.put("distance", distance);
                                    rideMap.put("fare", fare);
                                    rideMap.put("timestamp", ServerValue.TIMESTAMP);

                                    historyRef.setValue(rideMap)
                                            .addOnSuccessListener(aVoid1 -> {
                                                Log.d(TAG, "Ride history recorded successfully");
                                                // 3. Finally clear the driver's customer request
                                                DatabaseReference driverCustomerRequestRef = FirebaseDatabase.getInstance()
                                                        .getReference("Users/Drivers/" + userID + "/customerRequest");
                                                driverCustomerRequestRef.removeValue();
                                            })
                                            .addOnFailureListener(e -> Log.e(TAG, "Failed to record ride history", e));
                                }

                                @Override
                                public void onCancelled(@NonNull DatabaseError error) {
                                    Log.e(TAG, "Error getting customer info", error.toException());
                                }
                            });
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                            Log.e(TAG, "Error getting driver info", error.toException());
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to update customer request status", e);
                });
    }

    private double calculateRideDistance() {
        // Implement your actual distance calculation logic here
        // This is a placeholder - you should calculate based on actual route
        return 5.0; // 5 km as example
    }

    private double calculateFare(double distance) {
        // Implement your actual fare calculation logic here
        // Base fare + (distance * rate per km)
        return 3.0 + (distance * 1.5); // $3 base + $1.5 per km as example
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

    private ValueEventListener assignedCustomerRefListener;

    private void setupCustomerAssignmentListener() {
        if (userID == null) {
            Log.e(TAG, "User ID is null in setupCustomerAssignmentListener");
            return;
        }

        assignedCustomerRef = FirebaseDatabase.getInstance()
                .getReference("Users/Drivers/" + userID + "/customerRequest");

        assignedCustomerRefListener = assignedCustomerRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                try {
                    if (!dataSnapshot.exists()) {
                        // No customer assigned - reset state
                        handleRideEnded();
                        return;
                    }

                    // Check for cancellation status first
                    if (dataSnapshot.hasChild("status")) {
                        String status = dataSnapshot.child("status").getValue(String.class);
                        if ("canceled".equals(status)) {
                            handleRideCanceled();
                            return;
                        }
                    }

                    // Get customer ID if exists
                    String newCustomerID = dataSnapshot.child("customerRideID").getValue(String.class);
                    if (newCustomerID == null || newCustomerID.isEmpty()) {
                        handleRideEnded();
                        return;
                    }

                    // Check if customer request still exists
                    DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                            .getReference("CustomerRequest/" + newCustomerID);

                    customerRequestRef.addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            if (!snapshot.exists()) {
                                handleRideCanceled();
                                return;
                            }

                            // Only update if this is a different customer
                            if (!newCustomerID.equals(customerID)) {
                                customerID = newCustomerID;
                                showCustomerInfo();
                            }
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                            Log.e(TAG, "Error checking customer request", error.toException());
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error in customer assignment listener", e);
                    handleRideEnded();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Error checking assignment", databaseError.toException());
            }
        });
    }

    private void handleRideCanceled() {
        runOnUiThread(() -> {
            Toast.makeText(DriverMapActivity.this,
                    "Ride was canceled by customer",
                    Toast.LENGTH_SHORT).show();

            // Clear all customer info and reset UI
            clearCustomerInfo();
            status = 0;
            mRideStatus.setText("Available");

            // Remove the customer request from driver's node
            if (userID != null) {
                DatabaseReference driverRef = FirebaseDatabase.getInstance()
                        .getReference("Users/Drivers/" + userID + "/customerRequest");
                driverRef.removeValue();
            }

            // Also remove the customer's request if it still exists
            if (customerID != null && !customerID.isEmpty()) {
                DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                        .getReference("CustomerRequest/" + customerID);
                customerRequestRef.removeValue();
            }

            // Update driver availability
            if (mLastLocation != null) {
                updateDriverLocation(mLastLocation);
            }
        });
    }
    private void cancelRideFromDriver() {
        if (customerID == null || customerID.isEmpty()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Cancel Ride")
                .setMessage("Are you sure you want to cancel this ride?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    // Update status in both customer and driver nodes
                    Map<String, Object> updateMap = new HashMap<>();
                    updateMap.put("status", "canceled");

                    // Update customer request
                    DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                            .getReference("CustomerRequest/" + customerID);
                    customerRequestRef.updateChildren(updateMap);

                    // Update driver's assignment
                    if (userID != null) {
                        DatabaseReference driverRef = FirebaseDatabase.getInstance()
                                .getReference("Users/Drivers/" + userID + "/customerRequest");
                        driverRef.updateChildren(updateMap)
                                .addOnSuccessListener(aVoid -> {
                                    handleRideCanceled();
                                });
                    } else {
                        handleRideCanceled();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void cleanupAssignmentListener() {
        if (assignedCustomerRef != null && assignedCustomerRefListener != null) {
            assignedCustomerRef.removeEventListener(assignedCustomerRefListener);
        }
        assignedCustomerRefListener = null;
    }

    private void handleRideEnded() {
        if (customerID != null && !customerID.isEmpty()) {
            customerID = "";
            clearCustomerInfo();
            status = 0;
            mRideStatus.setText("Available");
        }
    }

    private void showCustomerInfo() {
        try {
            if (mMap == null) {
                Log.e(TAG, "Map is not ready yet");
                return;
            }
            mCustomerInfo.setVisibility(View.VISIBLE);
            status = 1;
            mRideStatus.setText("Picked Up Customer");

            // Get all customer information immediately
            getAssignedCustomerPickupLocation();
            getAssignedCustomerDestination();
            getAssignedCustomerInfo();

            // Center map on driver's current location if available
            if (mLastLocation != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(mLastLocation.getLatitude(), mLastLocation.getLongitude()),
                        15f));

                // Draw route from current location to pickup point immediately
                if (pickupMarker != null) {
                    getRouteToMarker(pickupMarker.getPosition());
                }

                // Also show destination marker immediately
                if (destinationLatLng != null) {
                    updateDestinationMarker(Arrays.asList(
                                    destinationLatLng.latitude,
                                    destinationLatLng.longitude),
                            destination);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing customer info", e);
            endRide();
            clearCustomerInfo();
        }
    }

    private void endRide() {
        runOnUiThread(() -> {
            // Clear UI first
            mRideStatus.setText("Available");
            status = 0;
            clearCustomerInfo();

            // Clean up Firebase references
            if (userID != null) {
                // Remove driver's customer request reference
                DatabaseReference driverRef = FirebaseDatabase.getInstance()
                        .getReference("Users/Drivers/" + userID + "/customerRequest");
                driverRef.removeValue().addOnCompleteListener(task -> {
                    // Remove customer request if exists
                    if (customerID != null && !customerID.isEmpty()) {
                        DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                                .getReference("CustomerRequest/" + customerID);
                        customerRequestRef.removeValue();
                    }

                    // Update GeoFire availability
                    DatabaseReference refWorking = FirebaseDatabase.getInstance()
                            .getReference("driversWorking");
                    new GeoFire(refWorking).removeLocation(userID, (key, error) -> {
                        if (mLastLocation != null) {
                            DatabaseReference refAvailable = FirebaseDatabase.getInstance()
                                    .getReference("driversAvailable");
                            new GeoFire(refAvailable).setLocation(userID,
                                    new GeoLocation(mLastLocation.getLatitude(), mLastLocation.getLongitude()));
                        }
                    });
                });
            }

            // Reset critical variables
            customerID = "";

            // Re-establish the assignment listener
            setupCustomerAssignmentListener();
        });
    }

    private void clearCustomerInfo() {
        runOnUiThread(() -> {
            mCustomerInfo.setVisibility(View.GONE);
            mCustomerName.setText("");
            mCustomerPhone.setText("");
            mCustomerDestination.setText("Destination: --");
            mCustomerProfileImage.setImageResource(R.drawable.account_circle_24);

            // Remove markers and polylines
            if (pickupMarker != null) {
                pickupMarker.remove();
                pickupMarker = null;
            }
            if (destinationMarker != null) {
                destinationMarker.remove();
                destinationMarker = null;
            }
            erasePolylines();

            // Reset customer data
            customerID = "";
            destination = null;
            destinationLatLng = null;

            // Remove pickup location listener
            if (assignedCustomerPickupLocationRef != null && assignedCustomerPickupLocationRefListener != null) {
                assignedCustomerPickupLocationRef.removeEventListener(assignedCustomerPickupLocationRefListener);
                assignedCustomerPickupLocationRefListener = null;
            }
        });
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

                    if (snapshot.getValue() instanceof Map) {
                        Map<String, Object> locationMap = (Map<String, Object>) snapshot.getValue();
                        if (locationMap.containsKey("0") && locationMap.containsKey("1")) {
                            updatePickupMarker(Arrays.asList(locationMap.get("0"), locationMap.get("1")));
                        }
                    } else if (snapshot.getValue() instanceof List) {
                        updatePickupMarker((List<Object>) snapshot.getValue());
                    } else {
                        Log.d(TAG, "Unknown location data format");
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
        runOnUiThread(() -> {
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

                // Draw route from current location to pickup point if we have location
                if (mLastLocation != null) {
                    getRouteToMarker(pickupLatLng);
                }

                // Zoom to show both driver and pickup if possible
                if (mLastLocation != null) {
                    LatLngBounds.Builder builder = new LatLngBounds.Builder();
                    builder.include(new LatLng(mLastLocation.getLatitude(), mLastLocation.getLongitude()));
                    builder.include(pickupLatLng);
                    mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100));
                } else {
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pickupLatLng, 15));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating pickup marker", e);
            }
        });
    }

    private void getRouteToMarker(LatLng destination) {
        if (mLastLocation == null || destination == null || mMap == null) {
            Log.e("ROUTE_ERROR", "Cannot calculate route - missing data");
            return;
        }

        erasePolylines();

        new Thread(() -> {
            try {

                GeoApiContext context = new GeoApiContext.Builder()
                        .apiKey("AIzaSyBoz_AvnAD8F8AS32u7k3tKas-lxqoXp1Q")
                        .build();

                DirectionsResult result = DirectionsApi.newRequest(context)
                        .origin(new com.google.maps.model.LatLng(
                                mLastLocation.getLatitude(),
                                mLastLocation.getLongitude()))
                        .destination(new com.google.maps.model.LatLng(
                                destination.latitude,
                                destination.longitude))
                        .mode(TravelMode.DRIVING)
                        .await();

                runOnUiThread(() -> {
                    if (result.routes != null && result.routes.length > 0) {
                        List<LatLng> decodedPath = PolyUtil.decode(
                                result.routes[0].overviewPolyline.getEncodedPath());

                        PolylineOptions polylineOptions = new PolylineOptions()
                                .addAll(decodedPath)
                                .color(Color.BLUE)
                                .width(10);

                        polylines.add(mMap.addPolyline(polylineOptions));

                        // Zoom to show the entire route
                        LatLngBounds.Builder builder = new LatLngBounds.Builder();
                        builder.include(new LatLng(
                                mLastLocation.getLatitude(),
                                mLastLocation.getLongitude()));
                        builder.include(destination);
                        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100));
                    }
                });
            } catch (Exception e) {
                Log.e("DirectionsAPI", "Error: " + e.getMessage());
                runOnUiThread(() ->
                        Toast.makeText(DriverMapActivity.this,
                                "Failed to get route: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
            }
        }).start();
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
        if (customerID == null || customerID.isEmpty()) {
            Log.d("Destination", "CustomerID is null or empty");
            return;
        }

        DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                .getReference("CustomerRequest/" + customerID);

        customerRequestRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                try {
                    if (!dataSnapshot.exists()) {
                        Log.d("Destination", "No customer request data");
                        return;
                    }

                    // Get destination name and update UI immediately
                    if (dataSnapshot.hasChild("destinationName")) {
                        destination = dataSnapshot.child("destinationName").getValue(String.class);
                    } else if (dataSnapshot.hasChild("destination")) {
                        destination = dataSnapshot.child("destination").getValue(String.class);
                    }

                    // Update UI with destination text
                    runOnUiThread(() -> {
                        if (destination != null && !destination.isEmpty()) {
                            mCustomerDestination.setText("Destination: " + destination);
                            mCustomerDestination.setVisibility(View.VISIBLE);
                        } else {
                            mCustomerDestination.setText("Destination: Not specified");
                        }
                    });

                    // Get destination coordinates
                    Object latLngObj = null;
                    if (dataSnapshot.hasChild("destinationLatLng")) {
                        latLngObj = dataSnapshot.child("destinationLatLng").getValue();
                    } else if (dataSnapshot.hasChild("destinationLat") && dataSnapshot.hasChild("destinationLng")) {
                        // Handle case where lat/lng are separate fields
                        double lat = dataSnapshot.child("destinationLat").getValue(Double.class);
                        double lng = dataSnapshot.child("destinationLng").getValue(Double.class);
                        latLngObj = Arrays.asList(lat, lng);
                    }

                    if (latLngObj != null) {
                        List<Object> latLng = new ArrayList<>();
                        if (latLngObj instanceof Map) {
                            Map<String, Object> map = (Map<String, Object>) latLngObj;
                            latLng.add(map.get("0"));
                            latLng.add(map.get("1"));
                        } else if (latLngObj instanceof List) {
                            latLng = (List<Object>) latLngObj;
                        }

                        if (latLng.size() >= 2) {
                            double lat = Double.parseDouble(latLng.get(0).toString());
                            double lng = Double.parseDouble(latLng.get(1).toString());
                            destinationLatLng = new LatLng(lat, lng);

                            // Update marker immediately
                            updateDestinationMarker(latLng, destination);

                            // Draw route if driver is enroute (status = 2) or if we have pickup location
                            if ((status == 2 || pickupMarker != null) && mLastLocation != null) {
                                getRouteToMarker(destinationLatLng);
                            }
                        }
                    } else {
                        Log.d("Destination", "No destination coordinates found");
                    }
                } catch (Exception e) {
                    Log.e("Destination", "Error processing destination", e);
                    runOnUiThread(() -> {
                        mCustomerDestination.setText("Destination: Error loading");
                        Toast.makeText(DriverMapActivity.this,
                                "Error loading destination details",
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e("Destination", "Database error", databaseError.toException());
                runOnUiThread(() -> {
                    mCustomerDestination.setText("Destination: Unavailable");
                    Toast.makeText(DriverMapActivity.this,
                            "Failed to load destination",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void updateDestinationMarker(List<Object> latLng, String destinationName) {
        runOnUiThread(() -> {
            try {
                if (mMap == null) {
                    Log.e("Destination", "Map is not ready");
                    return;
                }

                if (latLng == null || latLng.size() < 2) {
                    Log.e("Destination", "Invalid coordinates");
                    return;
                }

                double lat = Double.parseDouble(latLng.get(0).toString());
                double lng = Double.parseDouble(latLng.get(1).toString());
                LatLng destination = new LatLng(lat, lng);

                // Remove old marker if exists
                if (destinationMarker != null) {
                    destinationMarker.remove();
                }

                // Create new marker
                MarkerOptions options = new MarkerOptions()
                        .position(destination)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE));

                if (destinationName != null) {
                    options.title("Destination: " + destinationName);
                }

                destinationMarker = mMap.addMarker(options);

                // If we're enroute and have a pickup location, draw the route
                if (status == 2 && pickupMarker != null) {
                    getRouteToMarker(destination);
                }

                // Zoom to show both pickup and destination if possible
                if (pickupMarker != null) {
                    LatLngBounds.Builder builder = new LatLngBounds.Builder();
                    builder.include(pickupMarker.getPosition());
                    builder.include(destination);
                    mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100));
                } else {
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(destination, 15));
                }

            } catch (Exception e) {
                Log.e("Destination", "Failed to update marker", e);
            }
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        Log.d(TAG, "Map is now ready");  // Important debug log

        // 1. First set up basic map properties
        mMap.getUiSettings().setZoomControlsEnabled(true);
        mMap.getUiSettings().setMyLocationButtonEnabled(true);

        // 2. Set callback for when tiles are loaded (critical for marker visibility)
        mMap.setOnMapLoadedCallback(() -> {
            Log.d(TAG, "Map tiles fully loaded");
            redrawExistingMarkers();  // Will redraw any existing markers
        });

        // 3. Check permissions and enable location
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            setupLocationUpdates();
        } else {
            requestLocationPermissions();
        }
        //testMarkerPlacement();
    }

    // New helper method to redraw existing markers
    private void redrawExistingMarkers() {
        runOnUiThread(() -> {
            try {
                // Redraw pickup marker if we have customer data
                if (customerID != null && !customerID.isEmpty()) {
                    DatabaseReference pickupRef = FirebaseDatabase.getInstance()
                            .getReference("CustomerRequest/" + customerID + "/l");

                    pickupRef.get().addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            DataSnapshot snapshot = task.getResult();
                            if (snapshot.exists()) {
                                List<Object> location = (List<Object>) snapshot.getValue();
                                if (location != null && location.size() >= 2) {
                                    LatLng pickupLatLng = new LatLng(
                                            Double.parseDouble(location.get(0).toString()),
                                            Double.parseDouble(location.get(1).toString())
                                    );

                                    if (pickupMarker != null) {
                                        pickupMarker.remove();
                                    }
                                    pickupMarker = mMap.addMarker(new MarkerOptions()
                                            .position(pickupLatLng)
                                            .title("Pickup Location")
                                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                                }
                            }
                        }
                    });
                }

                // Redraw destination marker if exists
                if (destinationLatLng != null) {
                    if (destinationMarker != null) {
                        destinationMarker.remove();
                    }
                    destinationMarker = mMap.addMarker(new MarkerOptions()
                            .position(destinationLatLng)
                            .title(destination != null ? "Destination: " + destination : "Destination")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));

                    Log.d(TAG, "Destination marker redrawn at: " + destinationLatLng);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error redrawing markers", e);
            }
        });
    }

//    // Temporary test method (remove after verification)
//    private void testMarkerPlacement() {
//        new Handler().postDelayed(() -> {
//            if (mMap != null) {
//                // Clear any existing test markers/polylines first
//                erasePolylines();
//
//                // Test locations (San Francisco coordinates)
//                LatLng testPickup = new LatLng(37.7749, -122.4194);  // SF downtown
//                LatLng testDestination = new LatLng(37.3352, -122.0096);  // Cupertino
//
//                // Add pickup marker (red)
//                mMap.addMarker(new MarkerOptions()
//                        .position(testPickup)
//                        .title("TEST PICKUP")
//                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
//
//                // Add destination marker (blue)
//                mMap.addMarker(new MarkerOptions()
//                        .position(testDestination)
//                        .title("TEST DESTINATION")
//                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
//
//                // Zoom to show both markers
//                LatLngBounds bounds = new LatLngBounds.Builder()
//                        .include(testPickup)
//                        .include(testDestination)
//                        .build();
//                mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100));
//
//                // Test route drawing
//                new Thread(() -> {
//                    try {
//                        GeoApiContext context = new GeoApiContext.Builder()
//                                .apiKey("AIzaSyBoz_AvnAD8F8AS32u7k3tKas-lxqoXp1Q")
//                                .build();
//
//                        DirectionsResult result = DirectionsApi.newRequest(context)
//                                .origin(new com.google.maps.model.LatLng(
//                                        testPickup.latitude,
//                                        testPickup.longitude))
//                                .destination(new com.google.maps.model.LatLng(
//                                        testDestination.latitude,
//                                        testDestination.longitude))
//                                .mode(TravelMode.DRIVING)
//                                .await();
//
//                        runOnUiThread(() -> {
//                            if (result.routes != null && result.routes.length > 0) {
//                                List<LatLng> decodedPath = PolyUtil.decode(
//                                        result.routes[0].overviewPolyline.getEncodedPath());
//
//                                PolylineOptions polylineOptions = new PolylineOptions()
//                                        .addAll(decodedPath)
//                                        .color(Color.GREEN)  // Use green for test route
//                                        .width(12);
//
//                                polylines.add(mMap.addPolyline(polylineOptions));
//                                Log.d(TAG, "Test route drawn successfully");
//                            } else {
//                                Log.e(TAG, "No routes returned in test");
//                            }
//                        });
//                    } catch (Exception e) {
//                        Log.e(TAG, "Test route failed: " + e.getMessage());
//                        runOnUiThread(() ->
//                                Toast.makeText(this,
//                                        "Test route failed: " + e.getMessage(),
//                                        Toast.LENGTH_LONG).show());
//                    }
//                }).start();
//
//                Log.d(TAG, "Test markers placed at:\n" +
//                        "Pickup: " + testPickup + "\n" +
//                        "Destination: " + testDestination);
//            }
//        }, 3000);  // 3 second delay to ensure map is fully loaded
//    }

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

                Location location = locationResult.getLastLocation();
                if (location != null) {
                    mLastLocation = location;
                    updateDriverLocation(location);

                    // Auto-center map if no markers are present
                    if (pickupMarker == null && destinationMarker == null) {
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                new LatLng(location.getLatitude(), location.getLongitude()),
                                15f));
                    }
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationProviderClient.requestLocationUpdates(
                    locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    private void updateMapCamera(Location location) {
        if (mMap != null) {
            LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16));
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
                            } else {
                                Log.d(TAG, "Driver location updated in driversWorking: " + location.getLatitude() + ", " + location.getLongitude());
                            }
                        });

                DatabaseReference driverWorkingLocRef = FirebaseDatabase.getInstance().getReference("driversWorking/" + userID + "/l");
                List<Object> locationList = new ArrayList<>();
                locationList.add(location.getLatitude());
                locationList.add(location.getLongitude());
                driverWorkingLocRef.setValue(locationList)
                        .addOnSuccessListener(aVoid -> Log.d(TAG, "Driver location updated under driversWorking/l"))
                        .addOnFailureListener(e -> Log.e(TAG, "Failed to update driver location under driversWorking/l", e));
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
            DatabaseReference refAvailable = FirebaseDatabase.getInstance()
                    .getReference("driversAvailable");
            DatabaseReference refWorking = FirebaseDatabase.getInstance()
                    .getReference("driversWorking");
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

        // Clean up ride if active
        if (status != 0) { // 0 = available, 1 = picked up, 2 = enroute
            if (userID != null) {
                // Clear driver's customer request
                DatabaseReference driverRef = FirebaseDatabase.getInstance()
                        .getReference("Users/Drivers/" + userID + "/customerRequest");
                driverRef.removeValue();

                // Remove customer request if exists
                if (customerID != null && !customerID.isEmpty()) {
                    DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                            .getReference("CustomerRequest/" + customerID);
                    customerRequestRef.removeValue();
                }

                // Update driver availability
                DatabaseReference refWorking = FirebaseDatabase.getInstance()
                        .getReference("driversWorking");
                new GeoFire(refWorking).removeLocation(userID);
            }
        }

        // Remove location updates
        if (fusedLocationProviderClient != null && locationCallback != null) {
            try {
                fusedLocationProviderClient.removeLocationUpdates(locationCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error removing location updates", e);
            }
        }

        // Remove Firebase listeners
        if (assignedCustomerPickupLocationRefListener != null && assignedCustomerPickupLocationRef != null) {
            assignedCustomerPickupLocationRef.removeEventListener(assignedCustomerPickupLocationRefListener);
        }

        // Clean up GeoFire locations
        cleanupDriverLocation();

        // Clear all markers and polylines
        erasePolylines();
        removeAllMarkers();

        super.onDestroy();
    }

    private void erasePolylines() {
        for (Polyline line : polylines) {
            line.remove();
        }
        polylines.clear();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (status != 0) { // If driver is in a ride (1=picked up, 2=enroute)
            endRide();
        }
    }
}