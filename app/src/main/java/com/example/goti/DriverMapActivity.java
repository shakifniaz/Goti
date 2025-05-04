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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DriverMapActivity extends FragmentActivity implements OnMapReadyCallback {

    private static final String TAG = "DriverMapActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;

    // UI Components
    private GoogleMap mMap;
    private Button mLogout, mSettings, mRideStatus;
    private LinearLayout mCustomerInfo;
    private ImageView mCustomerProfileImage;
    private TextView mCustomerName, mCustomerPhone, mCustomerDestination;

    // Location Services
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private Location mLastLocation;

    // Firebase References
    private DatabaseReference driverLocationRef;
    private DatabaseReference assignedCustomerRef;
    private DatabaseReference assignedCustomerPickupLocationRef;
    private DatabaseReference customerRequestRef;
    private GeoFire geoFire;

    // State Variables
    private String userID;
    private String customerID = "";
    private String destination;
    private LatLng destinationLatLng;
    private int status = 0; // 0=available, 1=picked up, 2=enroute

    // Markers and Polylines
    private Marker pickupMarker, destinationMarker;
    private List<Polyline> polylines = new ArrayList<>();

    // Listeners
    private ValueEventListener assignedCustomerRefListener;
    private ValueEventListener assignedCustomerPickupLocationRefListener;
    private ValueEventListener customerRequestRefListener;

    // Safe handler for callbacks
    private final SafeHandler safeHandler = new SafeHandler(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_map);

        if (!Places.isInitialized()) {
            Places.initialize(getApplicationContext(), "AIzaSyBoz_AvnAD8F8AS32u7k3tKas-lxqoXp1Q");
        }

        initializeViews();
        setupFirebaseReferences();
        setupMap();
        setupLocationClient();
        setupButtonListeners();
        setupCustomerAssignmentListener();
    }

    private void initializeViews() {
        mRideStatus = findViewById(R.id.rideStatus);
        mCustomerInfo = findViewById(R.id.customerInfo);
        mCustomerProfileImage = findViewById(R.id.customerProfileImage);
        mCustomerName = findViewById(R.id.customerName);
        mCustomerPhone = findViewById(R.id.customerPhone);
        mCustomerDestination = findViewById(R.id.customerDestination);
        mLogout = findViewById(R.id.logout);
        mSettings = findViewById(R.id.settings);

        mRideStatus.setOnClickListener(v -> {
            if (isActivityDestroyed()) return;

            switch(status) {
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
                    completeRide();
                    break;
            }
        });
    }

    private void completeRide() {
        new AlertDialog.Builder(this)
                .setTitle("Complete Ride")
                .setMessage("Are you sure you want to complete this ride?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    recordRideCompletion();
                    mRideStatus.setText("Available");
                    status = 0;
                    clearCustomerInfo();
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void setupFirebaseReferences() {
        userID = FirebaseAuth.getInstance().getCurrentUser().getUid();
        if (userID == null) {
            finish();
            return;
        }

        driverLocationRef = FirebaseDatabase.getInstance().getReference("driversAvailable");
        customerRequestRef = FirebaseDatabase.getInstance().getReference("CustomerRequest");
        geoFire = new GeoFire(driverLocationRef);
    }

    private void setupMap() {
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
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

    private void setupCustomerAssignmentListener() {
        if (isActivityDestroyed() || userID == null) return;

        assignedCustomerRef = FirebaseDatabase.getInstance()
                .getReference("Users/Drivers/" + userID + "/customerRequest");

        assignedCustomerRefListener = assignedCustomerRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                safeHandler.updateUI(() -> {
                    try {
                        if (!dataSnapshot.exists()) {
                            handleRideEnded();
                            return;
                        }

                        if (dataSnapshot.hasChild("status")) {
                            String status = dataSnapshot.child("status").getValue(String.class);
                            if ("canceled".equals(status)) {
                                handleRideCanceled();
                                return;
                            }
                        }

                        String newCustomerID = dataSnapshot.child("customerRideID").getValue(String.class);
                        if (newCustomerID == null || newCustomerID.isEmpty()) {
                            handleRideEnded();
                            return;
                        }

                        if (!newCustomerID.equals(customerID)) {
                            customerID = newCustomerID;
                            setupCustomerRequestListener();
                            showCustomerInfo();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in customer assignment", e);
                        handleRideEnded();
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Assignment listener cancelled", databaseError.toException());
            }
        });
    }

    private void setupCustomerRequestListener() {
        if (customerID == null || customerID.isEmpty()) return;

        // Remove previous listener if exists
        if (customerRequestRefListener != null) {
            customerRequestRef.child(customerID).removeEventListener(customerRequestRefListener);
        }

        customerRequestRefListener = customerRequestRef.child(customerID).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    // Customer request was removed (completed or canceled)
                    handleRideEnded();
                } else if (snapshot.hasChild("status")) {
                    String status = snapshot.child("status").getValue(String.class);
                    if ("completed".equals(status) || "canceled".equals(status)) {
                        handleRideEnded();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Customer request listener cancelled", error.toException());
            }
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            setupLocationUpdates();
        }
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

                                    // Add destination information
                                    if (destination != null && !destination.isEmpty()) {
                                        rideMap.put("destination", destination);
                                    } else {
                                        rideMap.put("destination", "Unknown destination");
                                    }

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
        // Implement actual distance calculation based on pickup and destination
        return 5.0; // Placeholder
    }

    private double calculateFare(double distance) {
        return 3.0 + (distance * 1.5); // Placeholder calculation
    }

    private void handleRideCanceled() {
        safeHandler.updateUI(() -> {
            Toast.makeText(DriverMapActivity.this, "Ride was canceled", Toast.LENGTH_SHORT).show();
            clearCustomerInfo();
            status = 0;
            mRideStatus.setText("Available");

            if (userID != null) {
                DatabaseReference driverRef = FirebaseDatabase.getInstance()
                        .getReference("Users/Drivers/" + userID + "/customerRequest");
                driverRef.removeValue();
            }

            if (mLastLocation != null) {
                updateDriverLocation(mLastLocation);
            }
        });
    }

    private void cancelRideFromDriver() {
        if (customerID == null || customerID.isEmpty()) return;

        new AlertDialog.Builder(this)
                .setTitle("Cancel Ride")
                .setMessage("Are you sure you want to cancel this ride?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    // Update both customer request and driver assignment
                    Map<String, Object> updateMap = new HashMap<>();
                    updateMap.put("status", "canceled");

                    DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                            .getReference("CustomerRequest/" + customerID);
                    customerRequestRef.updateChildren(updateMap);

                    if (userID != null) {
                        DatabaseReference driverRef = FirebaseDatabase.getInstance()
                                .getReference("Users/Drivers/" + userID + "/customerRequest");
                        driverRef.updateChildren(updateMap);
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void handleRideEnded() {
        safeHandler.updateUI(() -> {
            if (customerID != null && !customerID.isEmpty()) {
                clearCustomerInfo();
                status = 0;
                mRideStatus.setText("Available");
            }
        });
    }

    private void showCustomerInfo() {
        safeHandler.updateUI(() -> {
            try {
                if (mMap == null) return;

                mCustomerInfo.setVisibility(View.VISIBLE);
                status = 1;
                mRideStatus.setText("Picked Up Customer");
                getAssignedCustomerPickupLocation();
                getAssignedCustomerDestination();
                getAssignedCustomerInfo();
            } catch (Exception e) {
                Log.e(TAG, "Error showing customer info", e);
                endRide();
                clearCustomerInfo();
            }
        });
    }

    private void endRide() {
        safeHandler.updateUI(() -> {
            mRideStatus.setText("Available");
            status = 0;
            clearCustomerInfo();

            if (userID != null) {
                DatabaseReference driverRef = FirebaseDatabase.getInstance()
                        .getReference("Users/Drivers/" + userID + "/customerRequest");
                driverRef.removeValue();

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
            }
            customerID = "";
        });
    }

    private void clearCustomerInfo() {
        safeHandler.updateUI(() -> {
            mCustomerInfo.setVisibility(View.GONE);
            mCustomerName.setText("");
            mCustomerPhone.setText("");
            mCustomerDestination.setText("Destination: --");
            mCustomerProfileImage.setImageResource(R.drawable.account_circle_24);

            if (pickupMarker != null) pickupMarker.remove();
            if (destinationMarker != null) destinationMarker.remove();
            erasePolylines();

            customerID = "";
            destination = null;
            destinationLatLng = null;

            if (assignedCustomerPickupLocationRef != null && assignedCustomerPickupLocationRefListener != null) {
                assignedCustomerPickupLocationRef.removeEventListener(assignedCustomerPickupLocationRefListener);
            }

            if (customerRequestRefListener != null && customerID != null && !customerID.isEmpty()) {
                customerRequestRef.child(customerID).removeEventListener(customerRequestRefListener);
            }
        });
    }

    private void getAssignedCustomerPickupLocation() {
        if (customerID == null || customerID.isEmpty()) return;

        assignedCustomerPickupLocationRef = FirebaseDatabase.getInstance()
                .getReference("CustomerRequest/" + customerID + "/l");

        assignedCustomerPickupLocationRefListener = assignedCustomerPickupLocationRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                safeHandler.updateUI(() -> {
                    try {
                        if (!snapshot.exists()) return;

                        List<Object> location = (List<Object>) snapshot.getValue();
                        if (location != null && location.size() >= 2) {
                            updatePickupMarker(location);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing pickup location", e);
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Pickup location error", error.toException());
            }
        });
    }

    private void updatePickupMarker(List<Object> location) {
        safeHandler.updateUI(() -> {
            try {
                if (mMap == null || location == null || location.size() < 2) return;

                double lat = Double.parseDouble(location.get(0).toString());
                double lng = Double.parseDouble(location.get(1).toString());
                LatLng pickupLatLng = new LatLng(lat, lng);

                if (pickupMarker != null) pickupMarker.remove();

                pickupMarker = mMap.addMarker(new MarkerOptions()
                        .position(pickupLatLng)
                        .title("Pickup Location")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));

                if (mLastLocation != null) {
                    getRouteToMarker(pickupLatLng);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating pickup marker", e);
            }
        });
    }

    private void getRouteToMarker(LatLng destination) {
        if (mLastLocation == null || destination == null || mMap == null) return;

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

                safeHandler.updateUI(() -> {
                    if (result.routes != null && result.routes.length > 0) {
                        List<LatLng> decodedPath = PolyUtil.decode(
                                result.routes[0].overviewPolyline.getEncodedPath());

                        PolylineOptions polylineOptions = new PolylineOptions()
                                .addAll(decodedPath)
                                .color(Color.BLUE)
                                .width(10);

                        polylines.add(mMap.addPolyline(polylineOptions));
                    }
                });
            } catch (Exception e) {
                Log.e("DirectionsAPI", "Error: " + e.getMessage());
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
                safeHandler.updateUI(() -> {
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
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Customer info error", error.toException());
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
                safeHandler.updateUI(() -> {
                    try {
                        if (!dataSnapshot.exists()) return;

                        if (dataSnapshot.hasChild("destinationName")) {
                            destination = dataSnapshot.child("destinationName").getValue(String.class);
                        } else if (dataSnapshot.hasChild("destination")) {
                            destination = dataSnapshot.child("destination").getValue(String.class);
                        }

                        if (destination != null && !destination.isEmpty()) {
                            mCustomerDestination.setText("Destination: " + destination);
                        } else {
                            mCustomerDestination.setText("Destination: Not specified");
                        }

                        Object latLngObj = null;
                        if (dataSnapshot.hasChild("destinationLatLng")) {
                            latLngObj = dataSnapshot.child("destinationLatLng").getValue();
                        } else if (dataSnapshot.hasChild("destinationLat") && dataSnapshot.hasChild("destinationLng")) {
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
                                updateDestinationMarker(latLng, destination);
                            }
                        }
                    } catch (Exception e) {
                        Log.e("Destination", "Error processing destination", e);
                        mCustomerDestination.setText("Destination: Error loading");
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e("Destination", "Database error", databaseError.toException());
            }
        });
    }

    private void updateDestinationMarker(List<Object> latLng, String destinationName) {
        safeHandler.updateUI(() -> {
            try {
                if (mMap == null || latLng == null || latLng.size() < 2) return;

                double lat = Double.parseDouble(latLng.get(0).toString());
                double lng = Double.parseDouble(latLng.get(1).toString());
                LatLng destination = new LatLng(lat, lng);

                if (destinationMarker != null) destinationMarker.remove();

                MarkerOptions options = new MarkerOptions()
                        .position(destination)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE));

                if (destinationName != null) {
                    options.title("Destination: " + destinationName);
                }

                destinationMarker = mMap.addMarker(options);
            } catch (Exception e) {
                Log.e("Destination", "Failed to update marker", e);
            }
        });
    }

    private void setupLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(5000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null || isActivityDestroyed()) return;

                Location location = locationResult.getLastLocation();
                if (location != null) {
                    mLastLocation = location;
                    updateDriverLocation(location);

                    if (pickupMarker == null && destinationMarker == null) {
                        safeHandler.updateUI(() -> {
                            if (mMap != null) {
                                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                        new LatLng(location.getLatitude(), location.getLongitude()),15f));
                            }
                        });
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

    private void updateDriverLocation(Location location) {
        if (location == null || userID == null || userID.isEmpty() || isActivityDestroyed()) return;

        try {
            DatabaseReference refAvailable = FirebaseDatabase.getInstance().getReference("driversAvailable");
            DatabaseReference refWorking = FirebaseDatabase.getInstance().getReference("driversWorking");
            GeoFire geoFireAvailable = new GeoFire(refAvailable);
            GeoFire geoFireWorking = new GeoFire(refWorking);

            if (customerID == null || customerID.isEmpty()) {
                geoFireWorking.removeLocation(userID);
                geoFireAvailable.setLocation(userID,
                        new GeoLocation(location.getLatitude(), location.getLongitude()));
            } else {
                geoFireAvailable.removeLocation(userID);
                geoFireWorking.setLocation(userID,
                        new GeoLocation(location.getLatitude(), location.getLongitude()));

                DatabaseReference driverWorkingLocRef = FirebaseDatabase.getInstance()
                        .getReference("driversWorking/" + userID + "/l");
                List<Object> locationList = Arrays.asList(location.getLatitude(), location.getLongitude());
                driverWorkingLocRef.setValue(locationList);
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
        } else {
            enableMyLocation();
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
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation();
            } else {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void cleanupDriverLocation() {
        if (userID == null || isActivityDestroyed()) return;

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

    private void logoutUser() {
        cleanupDriverLocation();

        if (fusedLocationProviderClient != null && locationCallback != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }

        FirebaseAuth.getInstance().signOut();
        startActivity(new Intent(DriverMapActivity.this, MainActivity.class));
        finish();
    }

    private void openSettings() {
        startActivity(new Intent(DriverMapActivity.this, DriverSettingsActivity.class));
    }

    private void erasePolylines() {
        safeHandler.updateUI(() -> {
            for (Polyline line : polylines) {
                line.remove();
            }
            polylines.clear();
        });
    }

    private boolean isActivityDestroyed() {
        return isFinishing() || isDestroyed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Clean up all resources
        if (assignedCustomerRef != null && assignedCustomerRefListener != null) {
            assignedCustomerRef.removeEventListener(assignedCustomerRefListener);
        }

        if (assignedCustomerPickupLocationRef != null && assignedCustomerPickupLocationRefListener != null) {
            assignedCustomerPickupLocationRef.removeEventListener(assignedCustomerPickupLocationRefListener);
        }

        if (customerRequestRefListener != null && customerID != null && !customerID.isEmpty()) {
            customerRequestRef.child(customerID).removeEventListener(customerRequestRefListener);
        }

        if (fusedLocationProviderClient != null && locationCallback != null) {
            try {
                fusedLocationProviderClient.removeLocationUpdates(locationCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error removing location updates", e);
            }
        }

        cleanupDriverLocation();
        new Handler().removeCallbacksAndMessages(null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (status != 0) {
            endRide();
        }
    }

    // Safe handler class to prevent memory leaks
    private static class SafeHandler {
        private final WeakReference<DriverMapActivity> activityRef;

        SafeHandler(DriverMapActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        void updateUI(Runnable update) {
            DriverMapActivity activity = activityRef.get();
            if (activity != null && !activity.isActivityDestroyed()) {
                activity.runOnUiThread(update);
            }
        }
    }
}