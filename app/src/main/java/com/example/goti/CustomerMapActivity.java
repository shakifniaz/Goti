package com.example.goti;

import static android.content.ContentValues.TAG;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Glide;
import com.directions.route.AbstractRouting;
import com.directions.route.Route;
import com.directions.route.RouteException;
import com.directions.route.Routing;
import com.directions.route.RoutingListener;
import com.firebase.geofire.GeoFire;
import com.firebase.geofire.GeoLocation;
import com.firebase.geofire.GeoQuery;
import com.firebase.geofire.GeoQueryEventListener;
import com.google.android.gms.common.api.Status;
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
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.widget.AutocompleteSupportFragment;
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.maps.DirectionsApi;
import com.google.maps.GeoApiContext;
import com.google.maps.android.PolyUtil;
import com.google.maps.model.DirectionsResult;
import com.google.maps.model.TravelMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomerMapActivity extends FragmentActivity implements OnMapReadyCallback, RoutingListener {

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private Location lastLocation;
    private Button mLogout, mRequest, mSettings, mHistory;
    private LatLng pickupLocation;
    private DatabaseReference customerRequestRef;
    private DatabaseReference driversAvailableRef;
    private DatabaseReference driversWorkingRef;
    private GeoFire geoFire;

    private enum RideState { NONE, REQUESTING, DRIVER_ASSIGNED }
    private RideState currentRideState = RideState.NONE;

    private int radius = 1;
    private boolean driverFound = false;
    private String driverFoundID;
    private Marker mDriverMarker, pickupMarker, destinationMarker;
    private GeoQuery geoQuery;
    private DatabaseReference driverLocationRef;
    private ValueEventListener driverLocationRefListener;
    private String destinationName, requestService;
    private LatLng destinationLatLng;
    private LinearLayout mDriverInfo;
    private ImageView mDriverProfileImage;
    private TextView mDriverName, mDriverPhone, mDriverCar, mFareEstimate;
    private RadioGroup mRadioGroup;
    private static final int MAX_SEARCH_RADIUS = 10; // km
    private static final long SEARCH_TIMEOUT = 30000; // 30 seconds
    private Handler searchTimeoutHandler;
    private LatLng customerCurrentLocation;
    private boolean isRouteVisible = false;
    private List<Polyline> polylines = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_map);

        searchTimeoutHandler = new Handler();
        RadioButton defaultRadio = findViewById(R.id.GotiX);
        requestService = defaultRadio != null ? defaultRadio.getText().toString() : "GotiX";

        if (!Places.isInitialized()) {
            Places.initialize(getApplicationContext(), "AIzaSyBoz_AvnAD8F8AS32u7k3tKas-lxqoXp1Q");
        }

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        destinationLatLng = new LatLng(0.0,0.0);

        mRequest = findViewById(R.id.request);
        mLogout = findViewById(R.id.logout);
        mSettings = findViewById(R.id.settings);
        mHistory = findViewById(R.id.history);
        mFareEstimate = findViewById(R.id.fareEstimate);

        mDriverInfo = findViewById(R.id.driverInfo);
        mDriverProfileImage = findViewById(R.id.driverProfileImage);
        mDriverName = findViewById(R.id.driverName);
        mDriverPhone = findViewById(R.id.driverPhone);
        mDriverCar = findViewById(R.id.driverCar);

        mRadioGroup = findViewById(R.id.radioGroup);
        mRadioGroup.check(R.id.GotiX);

        customerRequestRef = FirebaseDatabase.getInstance().getReference("CustomerRequest");
        driversAvailableRef = FirebaseDatabase.getInstance().getReference("driversAvailable");
        driversWorkingRef = FirebaseDatabase.getInstance().getReference("driversWorking");
        geoFire = new GeoFire(driversAvailableRef);

        requestLocationPermissions();
        setupUI();
        setupPlaceAutocomplete();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e("CRASH", "Uncaught exception", throwable);
            runOnUiThread(() -> {
                Toast.makeText(CustomerMapActivity.this,
                        "App crashed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
            });
            new Handler().postDelayed(() -> {
                Intent intent = new Intent(CustomerMapActivity.this, CustomerMapActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            }, 1000);
        });
    }

    private void setupUI() {
        mLogout.setOnClickListener(v -> logoutUser());
        mSettings.setOnClickListener(v -> openSettings());

        mRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton radioButton = findViewById(checkedId);
            if (radioButton != null) {
                requestService = radioButton.getText().toString();
                if (destinationLatLng != null && lastLocation != null) {
                    calculateFareEstimate();
                }
            }
        });

        mRequest.setOnClickListener(v -> {
            switch (currentRideState) {
                case NONE:
                    startRideRequest();
                    break;
                case REQUESTING:
                case DRIVER_ASSIGNED:
                    showCancelConfirmation();
                    break;
            }
        });

        mHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(CustomerMapActivity.this, HistoryActivity.class);
                intent.putExtra("customerOrDriver", "Customers");
                startActivity(intent);
                return;
            }
        });
    }

    private void calculateFareEstimate() {
        if (lastLocation == null || destinationLatLng == null) {
            return;
        }

        float[] results = new float[1];
        Location.distanceBetween(
                lastLocation.getLatitude(), lastLocation.getLongitude(),
                destinationLatLng.latitude, destinationLatLng.longitude,
                results);

        float distanceInKm = results[0] / 1000; // Convert meters to km

        double baseFare = 3.0; // Base fare
        double perKmRate = 1.5; // Rate per km
        double estimatedFare = baseFare + (distanceInKm * perKmRate);

        if (requestService != null) {
            switch (requestService) {
                case "GotiBlack":
                    estimatedFare *= 1.5;
                    break;
                case "GotiXl":
                    estimatedFare *= 2.0;
                    break;
                // GotiX uses base rate
            }
        }

        final double finalEstimatedFare = estimatedFare; // Create a final copy of the variable
        runOnUiThread(() -> {
            mFareEstimate.setVisibility(View.VISIBLE);
            mFareEstimate.setText(String.format("Fare estimate: Tk. %.2f", finalEstimatedFare));
        });
    }

    private void drawRouteToDestination() {
        if (lastLocation == null || destinationLatLng == null || mMap == null) {
            Log.e(TAG, "Cannot draw route - missing required parameters");
            return;
        }

        String apiKey;
        try {
            apiKey = getString(R.string.google_maps_key);
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("Google Maps API key not found");
            }
        } catch (Exception e) {
            Log.e(TAG, "API Key error: " + e.getMessage());
            Toast.makeText(this, "Configuration error: Missing API key", Toast.LENGTH_LONG).show();
            return;
        }

        LatLng origin = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());
        erasePolylines();

        try {
            GeoApiContext context = new GeoApiContext.Builder()
                    .apiKey(apiKey)
                    .build();

            DirectionsResult result = DirectionsApi.newRequest(context)
                    .origin(new com.google.maps.model.LatLng(origin.latitude, origin.longitude))
                    .destination(new com.google.maps.model.LatLng(
                            destinationLatLng.latitude,
                            destinationLatLng.longitude))
                    .mode(TravelMode.DRIVING)
                    .await();

            runOnUiThread(() -> {
                if (result.routes != null && result.routes.length > 0) {
                    List<LatLng> decodedPath = PolyUtil.decode(
                            result.routes[0].overviewPolyline.getEncodedPath());

                    PolylineOptions polylineOptions = new PolylineOptions()
                            .addAll(decodedPath)
                            .color(Color.BLUE)
                            .width(12);

                    polylines.add(mMap.addPolyline(polylineOptions));

                    LatLngBounds.Builder builder = new LatLngBounds.Builder();
                    builder.include(origin);
                    builder.include(destinationLatLng);
                    mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100));

                    calculateFareEstimate();
                } else {
                    Toast.makeText(CustomerMapActivity.this,
                            "No route found between locations",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Routing error: " + e.getMessage(), e);
            runOnUiThread(() -> {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown routing error";
                if (errorMsg.contains("API_KEY")) {
                    Toast.makeText(CustomerMapActivity.this,
                            "API Key Error: Please check your Google Maps configuration",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(CustomerMapActivity.this,
                            "Route Error: " + errorMsg,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void updateRoute() {
        if (isRouteVisible && customerCurrentLocation != null && destinationLatLng != null) {
            drawRouteToDestination();
        }
    }

    private void logoutUser() {
        cancelRideRequest();
        FirebaseAuth.getInstance().signOut();
        startActivity(new Intent(CustomerMapActivity.this, MainActivity.class));
        finish();
    }

    private void openSettings() {
        startActivity(new Intent(CustomerMapActivity.this, CustomerSettingsActivity.class));
    }

    private void showCancelConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Cancel Ride")
                .setMessage("Are you sure you want to cancel this ride?")
                .setPositiveButton("Yes", (dialog, which) -> cancelRideRequest())
                .setNegativeButton("No", null)
                .show();
    }

    private void setupPlaceAutocomplete() {
        AutocompleteSupportFragment autocompleteFragment = (AutocompleteSupportFragment)
                getSupportFragmentManager().findFragmentById(R.id.autocomplete_fragment);

        autocompleteFragment.setPlaceFields(Arrays.asList(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG));
        autocompleteFragment.setOnPlaceSelectedListener(new PlaceSelectionListener() {
            @Override
            public void onPlaceSelected(@NonNull Place place) {
                try {
                    if (place.getLatLng() != null) {
                        destinationLatLng = place.getLatLng();
                        destinationName = place.getName();
                        updateDestinationMarker();
                        if (lastLocation != null) {
                            drawRouteToDestination();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Place selection error", e);
                    Toast.makeText(CustomerMapActivity.this,
                            "Error selecting place: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(@NonNull Status status) {
                Log.e(TAG, "Place selection error: " + status.getStatusMessage());
                Toast.makeText(CustomerMapActivity.this,
                        "Place selection error: " + status.getStatusMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void getRouteToMarker(LatLng origin, LatLng destination) {
        erasePolylines();

        Routing routing = new Routing.Builder()
                .travelMode(AbstractRouting.TravelMode.DRIVING)
                .withListener(this)
                .alternativeRoutes(false)
                .waypoints(origin, destination)
                .build();
        routing.execute();
    }

    private void updateDestinationMarker() {
        runOnUiThread(() -> {
            if (mMap == null) {
                Log.e(TAG, "Map not initialized");
                return;
            }

            if (destinationMarker != null) {
                destinationMarker.remove();
            }

            if (destinationLatLng != null && destinationName != null) {
                destinationMarker = mMap.addMarker(new MarkerOptions()
                        .position(destinationLatLng)
                        .title("Destination: " + destinationName)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
            }
        });
    }

    private void startRideRequest() {
        if (lastLocation == null) {
            Toast.makeText(this, "Location not available yet", Toast.LENGTH_SHORT).show();
            return;
        }

        if (destinationName == null || destinationLatLng == null) {
            Toast.makeText(this, "Please select a valid destination first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (requestService == null || requestService.isEmpty()) {
            Toast.makeText(this, "Please select a service type", Toast.LENGTH_SHORT).show();
            return;
        }

        currentRideState = RideState.REQUESTING;
        String userID = FirebaseAuth.getInstance().getCurrentUser().getUid();

        try {
            mRequest.setText("Finding Driver...");
            mRequest.setEnabled(false);

            HashMap<String, Object> customerRequest = new HashMap<>();
            customerRequest.put("l", Arrays.asList(lastLocation.getLatitude(), lastLocation.getLongitude()));
            customerRequest.put("destinationName", destinationName);
            customerRequest.put("destinationLatLng", Arrays.asList(destinationLatLng.latitude, destinationLatLng.longitude));
            customerRequest.put("service", requestService);
            customerRequest.put("status", "requesting");

            customerRequestRef.child(userID).setValue(customerRequest)
                    .addOnCompleteListener(task -> {
                        mRequest.setEnabled(true);
                        if (!task.isSuccessful()) {
                            Log.e("Firebase", "Error setting location", task.getException());
                            Toast.makeText(this, "Failed to request ride", Toast.LENGTH_SHORT).show();
                            resetRideRequest();
                            return;
                        }

                        try {
                            pickupLocation = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());
                            updatePickupMarker(pickupLocation);
                            getClosestDriver();
                        } catch (Exception e) {
                            Log.e("RideRequest", "Error processing location", e);
                            Toast.makeText(this, "Error processing location", Toast.LENGTH_SHORT).show();
                            resetRideRequest();
                        }
                    });
        } catch (Exception e) {
            Log.e("RideRequest", "Error starting ride request", e);
            Toast.makeText(this, "Error starting ride request", Toast.LENGTH_SHORT).show();
            resetRideRequest();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (mMap != null) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        mMap.setMyLocationEnabled(true);
                        setupLocationUpdates();
                    }
                }
            } else {
                Toast.makeText(this, "Location permission is required for this app to function", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void updatePickupMarker(LatLng location) {
        runOnUiThread(() -> {
            if (pickupMarker != null) {
                pickupMarker.remove();
            }
            pickupMarker = mMap.addMarker(new MarkerOptions()
                    .position(location)
                    .title("Your Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        });
    }

    private void getClosestDriver() {
        if (pickupLocation == null) {
            Log.e("GeoQuery", "Pickup location is null");
            resetRideRequest();
            return;
        }

        searchTimeoutHandler.removeCallbacksAndMessages(null);

        searchTimeoutHandler.postDelayed(() -> {
            if (!driverFound && currentRideState == RideState.REQUESTING) {
                handleNoDriversAvailable();
            }
        }, SEARCH_TIMEOUT);

        if (geoQuery != null) {
            geoQuery.removeAllListeners();
        }

        GeoFire availableDriversGeoFire = new GeoFire(driversAvailableRef);
        geoQuery = availableDriversGeoFire.queryAtLocation(
                new GeoLocation(pickupLocation.latitude, pickupLocation.longitude),
                radius);

        geoQuery.addGeoQueryEventListener(new GeoQueryEventListener() {
            @Override
            public void onKeyEntered(String key, GeoLocation location) {
                if (!driverFound && currentRideState == RideState.REQUESTING) {
                    DatabaseReference mCustomerDatabase = FirebaseDatabase.getInstance().getReference()
                            .child("Users").child("Drivers").child(key);
                    mCustomerDatabase.addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            if(snapshot.exists() && snapshot.getChildrenCount()>0){
                                Map<String, Object> driverMap = (Map<String, Object>) snapshot.getValue();
                                if(driverFound){
                                    return;
                                }

                                if(requestService != null && requestService.equals(driverMap.get("service"))){
                                    driverFound = true;
                                    driverFoundID = snapshot.getKey();
                                    searchTimeoutHandler.removeCallbacksAndMessages(null);
                                    assignDriverToCustomer();
                                }
                            }
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                        }
                    });
                }
            }

            @Override public void onKeyExited(String key) {
                if (driverFound && key.equals(driverFoundID)) {
                    handleDriverUnavailable();
                }
            }

            @Override public void onKeyMoved(String key, GeoLocation location) {}

            @Override
            public void onGeoQueryReady() {
                if (!driverFound && radius <= MAX_SEARCH_RADIUS) {
                    radius++;
                    getClosestDriver();
                } else if (!driverFound) {
                    handleNoDriversAvailable();
                }
            }

            @Override
            public void onGeoQueryError(DatabaseError error) {
                handleGeoQueryError(error);
            }
        });
    }

    private void assignDriverToCustomer() {
        DatabaseReference driverRef = FirebaseDatabase.getInstance()
                .getReference("Users/Drivers/" + driverFoundID + "/customerRequest");

        HashMap<String, Object> map = new HashMap<>();
        map.put("customerRideID", FirebaseAuth.getInstance().getCurrentUser().getUid());
        map.put("destination", destinationName);
        map.put("service", requestService);
        map.put("destinationLat", destinationLatLng.latitude);
        map.put("destinationLng", destinationLatLng.longitude);
        map.put("status", "assigned");

        driverRef.updateChildren(map).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                currentRideState = RideState.DRIVER_ASSIGNED;
                mRequest.setText("Tracking Driver...");
                mDriverInfo.setVisibility(View.VISIBLE);
                getDriverLocation();
                getHasRideEnded();
                getAssignedDriverInfo();

                String userID = FirebaseAuth.getInstance().getCurrentUser().getUid();
                customerRequestRef.child(userID).child("status").setValue("assigned");
            } else {
                handleDriverAssignmentFailed();
            }
        });
    }

    private void getAssignedDriverInfo() {
        DatabaseReference mCustomerDatabase = FirebaseDatabase.getInstance().getReference()
                .child("Users/Drivers/" + driverFoundID);

        mCustomerDatabase.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    if (snapshot.hasChild("name")) {
                        mDriverName.setText(snapshot.child("name").getValue(String.class));
                    }
                    if (snapshot.hasChild("phone")) {
                        mDriverPhone.setText(snapshot.child("phone").getValue(String.class));
                    }
                    if (snapshot.hasChild("car")) {
                        mDriverCar.setText(snapshot.child("car").getValue(String.class));
                    }
                    if (snapshot.hasChild("profileImageUrl")) {
                        Glide.with(CustomerMapActivity.this)
                                .load(snapshot.child("profileImageUrl").getValue(String.class))
                                .into(mDriverProfileImage);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("CustomerInfo", "Error: " + error.getMessage());
            }
        });
    }

    private DatabaseReference driveHasEndedRef;
    private ValueEventListener driveHasEndedRefListener;
    public void getHasRideEnded(){
        driveHasEndedRef = FirebaseDatabase.getInstance().getReference()
                .child("Users").child("Drivers")
                .child(driverFoundID).child("customerRequest");
        driveHasEndedRefListener = driveHasEndedRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if(!snapshot.exists()){
                    endRide();
                } else if (snapshot.child("status").exists() &&
                        "completed".equals(snapshot.child("status").getValue(String.class))) {
                    endRide();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        });
    }

    private void endRide(){
        if (driveHasEndedRefListener != null && driveHasEndedRef != null) {
            driveHasEndedRef.removeEventListener(driveHasEndedRefListener);
        }

        if (driverFoundID != null && !driverFoundID.isEmpty()) {
            DatabaseReference driverRef = FirebaseDatabase.getInstance()
                    .getReference("Users/Drivers/" + driverFoundID + "/customerRequest");
            driverRef.removeValue();
        }

        String userID = FirebaseAuth.getInstance().getCurrentUser().getUid();
        customerRequestRef.child(userID).removeValue();

        runOnUiThread(() -> {
            mDriverInfo.setVisibility(View.GONE);
            mDriverName.setText("");
            mDriverPhone.setText("");
            mDriverCar.setText("");
            mDriverProfileImage.setImageResource(R.drawable.account_circle_24);
            mRequest.setText("Request Ride");
            mRequest.setEnabled(true);
            mFareEstimate.setVisibility(View.GONE);

            if (mDriverMarker != null) {
                mDriverMarker.remove();
                mDriverMarker = null;
            }
            if (pickupMarker != null) {
                pickupMarker.remove();
                pickupMarker = null;
            }
            if (destinationMarker != null) {
                destinationMarker.remove();
                destinationMarker = null;
            }

            erasePolylines();
        });

        currentRideState = RideState.NONE;
        driverFound = false;
        driverFoundID = "";
    }

    private void handleDriverUnavailable() {
        Toast.makeText(this, "Driver became unavailable", Toast.LENGTH_SHORT).show();
        resetRideRequest();
    }

    private void handleNoDriversAvailable() {
        Toast.makeText(this, "No drivers available", Toast.LENGTH_SHORT).show();
        resetRideRequest();
    }

    private void handleGeoQueryError(DatabaseError error) {
        Log.e("GeoQuery", "Error: " + error.getMessage());
        Toast.makeText(this, "Error searching for drivers", Toast.LENGTH_SHORT).show();
        resetRideRequest();
    }

    private void handleDriverAssignmentFailed() {
        Log.e("DriverAssign", "Failed to assign driver");
        Toast.makeText(this, "Failed to assign driver", Toast.LENGTH_SHORT).show();
        resetRideRequest();
    }

    private void getDriverLocation() {
        if (driverFoundID == null || driverFoundID.isEmpty()) {
            Toast.makeText(this, "Driver ID not available", Toast.LENGTH_SHORT).show();
            return;
        }

        driverLocationRef = FirebaseDatabase.getInstance().getReference("driversWorking").child(driverFoundID).child("l");

        if (driverLocationRefListener != null) {
            driverLocationRef.removeEventListener(driverLocationRefListener);
        }

        driverLocationRefListener = driverLocationRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(CustomerMapActivity.this, "Driver location not available!", Toast.LENGTH_SHORT).show();
                    Log.e("DriverLocation", "Snapshot does not exist at path: " + snapshot.getRef().toString());
                    return;
                }

                try {
                    List<Object> location = (List<Object>) snapshot.getValue();
                    if (location != null && location.size() >= 2) {
                        Log.d("DriverLocation", "Driver location updated: " + location.get(0) + ", " + location.get(1));
                        updateDriverMarker(location);
                    } else {
                        Log.e("DriverLocation", "Invalid location data format");
                    }
                } catch (Exception e) {
                    Log.e("LocationParse", "Error parsing location", e);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("DriverLocation", "Error: " + error.getMessage());
            }
        });
    }

    private void updateDriverMarker(List<Object> location) {
        double lat = Double.parseDouble(location.get(0).toString());
        double lng = Double.parseDouble(location.get(1).toString());
        LatLng driverLatLng = new LatLng(lat, lng);

        if (mDriverMarker != null) {
            mDriverMarker.remove();
        }

        mDriverMarker = mMap.addMarker(new MarkerOptions()
                .position(driverLatLng)
                .title("Your Driver")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));

        if (pickupLocation != null) {
            updateDriverDistance(driverLatLng);
        }
    }

    private void updateDriverDistance(LatLng driverLatLng) {
        Location pickupLoc = new Location("");
        pickupLoc.setLatitude(pickupLocation.latitude);
        pickupLoc.setLongitude(pickupLocation.longitude);

        Location driverLoc = new Location("");
        driverLoc.setLatitude(driverLatLng.latitude);
        driverLoc.setLongitude(driverLatLng.longitude);

        float distance = pickupLoc.distanceTo(driverLoc);
        mRequest.setText(distance < 100 ? "Driver Arrived" :
                String.format("Driver: %.1f meters away", distance));
    }

    private void cancelRideRequest() {
        String userID = FirebaseAuth.getInstance().getCurrentUser().getUid();

        customerRequestRef.child(userID).removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d("CancelRide", "Customer request removed");
                    clearDriverAssignment();
                })
                .addOnFailureListener(e -> {
                    Log.e("CancelRide", "Error removing request", e);
                    Toast.makeText(this, "Failed to cancel ride", Toast.LENGTH_SHORT).show();
                });
    }

    private void clearDriverAssignment() {
        if (driverFoundID != null && !driverFoundID.isEmpty()) {
            DatabaseReference driverRef = FirebaseDatabase.getInstance()
                    .getReference("Users/Drivers/" + driverFoundID + "/customerRequest");
            driverRef.removeValue();
        }

        mDriverInfo.setVisibility(View.GONE);
        mDriverName.setText("");
        mDriverPhone.setText("");
        mDriverCar.setText("");
        mDriverProfileImage.setImageResource(R.drawable.account_circle_24);
        mFareEstimate.setVisibility(View.GONE);

        cleanupRide();
    }

    private void cleanupRide() {
        searchTimeoutHandler.removeCallbacksAndMessages(null);

        if (geoQuery != null) {
            geoQuery.removeAllListeners();
        }

        if (driverLocationRefListener != null && driverLocationRef != null) {
            driverLocationRef.removeEventListener(driverLocationRefListener);
        }

        removeMarkers();
        resetRideState();
        updateUI();
    }

    private void removeMarkers() {
        runOnUiThread(() -> {
            try {
                if (mDriverMarker != null) {
                    mDriverMarker.remove();
                    mDriverMarker = null;
                }
                if (pickupMarker != null) {
                    pickupMarker.remove();
                    pickupMarker = null;
                }
                if (destinationMarker != null) {
                    destinationMarker.remove();
                    destinationMarker = null;
                }
            } catch (Exception e) {
                Log.e("Markers", "Error removing markers", e);
            }
        });
    }

    private void resetRideState() {
        radius = 1;
        driverFound = false;
        driverFoundID = "";
        currentRideState = RideState.NONE;
        destinationName = null;
        destinationLatLng = null;
    }

    private void updateUI() {
        mRequest.setText("Request Ride");
        mRequest.setEnabled(true);
        mFareEstimate.setVisibility(View.GONE);
    }

    private void resetRideRequest() {
        currentRideState = RideState.NONE;
        mRequest.setText("Request Ride");
        mFareEstimate.setVisibility(View.GONE);
    }

    private void requestLocationPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            setupLocationUpdates();
        } else {
            requestLocationPermissions();
        }
    }

    private void setupLocationUpdates() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, "Please enable location services", Toast.LENGTH_LONG).show();
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(5000)
                .setMaxUpdateDelayMillis(10000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null || mMap == null) {
                    Log.e(TAG, "Null location result or map");
                    return;
                }

                Location location = locationResult.getLastLocation();
                if (location != null) {
                    lastLocation = location;
                    customerCurrentLocation = new LatLng(location.getLatitude(), location.getLongitude());
                    updatePickupMarker(customerCurrentLocation);
                    updateRoute();
                    if (destinationLatLng != null) {
                        calculateFareEstimate();
                    }
                }
            }
        };

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission exception", e);
            Toast.makeText(this, "Location permission error", Toast.LENGTH_LONG).show();
        }
    }

    private void updateCurrentLocationMarker(LatLng location) {
        runOnUiThread(() -> {
            if (pickupMarker != null) {
                pickupMarker.remove();
            }
            pickupMarker = mMap.addMarker(new MarkerOptions()
                    .position(location)
                    .title("Your Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        searchTimeoutHandler.removeCallbacksAndMessages(null);
        cleanupRide();
        if (fusedLocationProviderClient != null && locationCallback != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }
    }

    @Override
    public void onRoutingFailure(RouteException e) {
        Log.e(TAG, "Routing failed: " + e.getMessage());
        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRoutingStart() {}

    @Override
    public void onRoutingSuccess(ArrayList<Route> routes, int shortestRouteIndex) {
        if (polylines.size() > 0) {
            for (Polyline poly : polylines) {
                poly.remove();
            }
            polylines.clear();
        }

        for (Route route : routes) {
            PolylineOptions polyOptions = new PolylineOptions();
            polyOptions.color(Color.BLUE);
            polyOptions.width(10);
            polyOptions.addAll(route.getPoints());
            Polyline polyline = mMap.addPolyline(polyOptions);
            polylines.add(polyline);
        }
    }

    @Override
    public void onRoutingCancelled() {}

    private void erasePolylines() {
        runOnUiThread(() -> {
            for (Polyline line : polylines) {
                line.remove();
            }
            polylines.clear();
        });
    }
}