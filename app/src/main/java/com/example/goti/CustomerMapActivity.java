package com.example.goti;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

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
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.widget.AutocompleteSupportFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class CustomerMapActivity extends FragmentActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private Location lastLocation;
    private Button mLogout, mRequest, mSettings;
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
    private String destinationName;
    private LatLng destinationLatLng;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_map);

        if (!Places.isInitialized()) {
            Places.initialize(getApplicationContext(), "YOUR_API_KEY");
        }

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        mRequest = findViewById(R.id.request);
        mLogout = findViewById(R.id.logout);
        mSettings = findViewById(R.id.settings);

        customerRequestRef = FirebaseDatabase.getInstance().getReference("CustomerRequest");
        driversAvailableRef = FirebaseDatabase.getInstance().getReference("driversAvailable");
        driversWorkingRef = FirebaseDatabase.getInstance().getReference("driversWorking");
        geoFire = new GeoFire(driversAvailableRef);

        requestLocationPermissions();
        setupUI();
        setupPlaceAutocomplete();
    }

    private void setupUI() {
        mLogout.setOnClickListener(v -> logoutUser());
        mSettings.setOnClickListener(v -> openSettings());

        mRequest.setOnClickListener(v -> {
            switch (currentRideState) {
                case NONE:
                    if (destinationName == null) {
                        Toast.makeText(this, "Please select a destination first", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startRideRequest();
                    break;
                case REQUESTING:
                case DRIVER_ASSIGNED:
                    showCancelConfirmation();
                    break;
            }
        });
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
        autocompleteFragment.setOnPlaceSelectedListener(new com.google.android.libraries.places.widget.listener.PlaceSelectionListener() {
            @Override
            public void onPlaceSelected(Place place) {
                destinationName = place.getName();
                destinationLatLng = place.getLatLng();
                updateDestinationMarker();
            }

            @Override
            public void onError(Status status) {
                Log.e("PlaceError", "Error: " + status);
            }
        });
    }

    private void updateDestinationMarker() {
        if (destinationMarker != null) {
            destinationMarker.remove();
        }
        destinationMarker = mMap.addMarker(new MarkerOptions()
                .position(destinationLatLng)
                .title("Destination: " + destinationName)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
    }

    private void startRideRequest() {
        if (lastLocation == null) {
            Toast.makeText(this, "Location not available yet", Toast.LENGTH_SHORT).show();
            return;
        }

        currentRideState = RideState.REQUESTING;
        String userID = FirebaseAuth.getInstance().getCurrentUser().getUid();

        mRequest.setText("Finding Driver...");
        mRequest.setEnabled(false);

        HashMap<String, Object> customerRequest = new HashMap<>();
        customerRequest.put("l", Arrays.asList(lastLocation.getLatitude(), lastLocation.getLongitude()));
        customerRequest.put("destinationName", destinationName);
        customerRequest.put("destinationLatLng", Arrays.asList(destinationLatLng.latitude, destinationLatLng.longitude));

        customerRequestRef.child(userID).setValue(customerRequest)
                .addOnCompleteListener(task -> {
                    mRequest.setEnabled(true);
                    if (!task.isSuccessful()) {
                        Log.e("Firebase", "Error setting location", task.getException());
                        Toast.makeText(this, "Failed to request ride", Toast.LENGTH_SHORT).show();
                        resetRideRequest();
                        return;
                    }

                    pickupLocation = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());
                    updatePickupMarker();
                    getClosestDriver();
                });
    }

    private void updatePickupMarker() {
        if (pickupMarker != null) {
            pickupMarker.remove();
        }
        pickupMarker = mMap.addMarker(new MarkerOptions()
                .position(pickupLocation)
                .title("Pickup Here")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
    }

    private void getClosestDriver() {
        if (pickupLocation == null) {
            Log.e("GeoQuery", "Pickup location is null");
            resetRideRequest();
            return;
        }

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
                    driverFound = true;
                    driverFoundID = key;
                    assignDriverToCustomer();
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
                if (!driverFound && radius <= 10) {
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

        driverRef.updateChildren(map).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                currentRideState = RideState.DRIVER_ASSIGNED;
                mRequest.setText("Tracking Driver...");
                getDriverLocation();
            } else {
                handleDriverAssignmentFailed();
            }
        });
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

        driverLocationRef = driversWorkingRef.child(driverFoundID).child("l");

        if (driverLocationRefListener != null) {
            driverLocationRef.removeEventListener(driverLocationRefListener);
        }

        driverLocationRefListener = driverLocationRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(CustomerMapActivity.this, "Driver location not available!", Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    List<Object> location = (List<Object>) snapshot.getValue();
                    if (location != null && location.size() >= 2) {
                        updateDriverMarker(location);
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
        cleanupRide();
    }

    private void cleanupRide() {
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
    }

    private void resetRideRequest() {
        currentRideState = RideState.NONE;
        mRequest.setText("Request Ride");
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
                        lastLocation = location;
                        LatLng userLocation = new LatLng(location.getLatitude(), location.getLongitude());
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 15));
                    }
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupRide();
        if (fusedLocationProviderClient != null && locationCallback != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }
    }
}