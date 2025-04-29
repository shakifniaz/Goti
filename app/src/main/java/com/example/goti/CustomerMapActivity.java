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
    private boolean firstLocationUpdate = true;
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
    private Marker mDriverMarker;
    private Marker pickupMarker;
    private GeoQuery geoQuery;
    private DatabaseReference driverLocationRef;
    private ValueEventListener driverLocationRefListener;
    private String destination;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_map);

        // Initialize Places API
        if (!Places.isInitialized()) {
            Places.initialize(getApplicationContext(), "AIzaSyBBeBYZWT8XKIvKWONhScmwWRWpNdA_7jA"); // Replace with your API key
        }

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        mRequest = findViewById(R.id.request);
        mLogout = findViewById(R.id.logout);
        mSettings = findViewById(R.id.settings);

        // Initialize Firebase references
        customerRequestRef = FirebaseDatabase.getInstance().getReference("CustomerRequest");
        driversAvailableRef = FirebaseDatabase.getInstance().getReference("driversAvailable");
        driversWorkingRef = FirebaseDatabase.getInstance().getReference("driversWorking");
        geoFire = new GeoFire(customerRequestRef);

        requestLocationPermissions();

        mLogout.setOnClickListener(v -> {
            cancelRideRequest();
            FirebaseAuth.getInstance().signOut();
            Intent intent = new Intent(CustomerMapActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        });

        mSettings.setOnClickListener(v -> {
            Intent intent = new Intent(CustomerMapActivity.this, CustomerSettingsActivity.class);
            startActivity(intent);
        });

        mRequest.setOnClickListener(v -> {
            switch (currentRideState) {
                case NONE:
                    startRideRequest();
                    break;
                case REQUESTING:
                case DRIVER_ASSIGNED:
                    cancelRideRequest();
                    break;
            }
        });

        // Initialize PlaceAutocompleteFragment for destination input
        AutocompleteSupportFragment autocompleteFragment =
                (AutocompleteSupportFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.autocomplete_fragment);

        autocompleteFragment.setPlaceFields(Arrays.asList(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG));
        autocompleteFragment.setOnPlaceSelectedListener(new com.google.android.libraries.places.widget.listener.PlaceSelectionListener() {
            @Override
            public void onPlaceSelected(Place place) {
                // Handle place selection (set destination)
                destination = place.getName();
                Log.d("PlaceSelected", "Place: " + place.getName());
            }

            @Override
            public void onError(Status status) {
                Log.e("PlaceError", "Error: " + status);
            }
        });
    }

    private void startRideRequest() {
        if (lastLocation == null) {
            Toast.makeText(this, "Location not available yet", Toast.LENGTH_SHORT).show();
            return;
        }

        currentRideState = RideState.REQUESTING;
        String userID = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // Update UI
        mRequest.setText("Finding Driver...");
        mRequest.setEnabled(false);

        // Set pickup location in database
        geoFire.setLocation(userID, new GeoLocation(lastLocation.getLatitude(), lastLocation.getLongitude()), (key, error) -> {
            mRequest.setEnabled(true);
            if (error != null) {
                Log.e("GeoFire", "Error setting location: " + error.getMessage());
                Toast.makeText(this, "Failed to request ride", Toast.LENGTH_SHORT).show();
                resetRideRequest();
                return;
            }

            Log.d("GeoFire", "Pickup location set successfully");
            pickupLocation = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());

            // Clear previous markers
            if (pickupMarker != null) {
                pickupMarker.remove();
            }

            // Add pickup marker
            pickupMarker = mMap.addMarker(new MarkerOptions()
                    .position(pickupLocation)
                    .title("Pickup Here")
                    .icon(BitmapDescriptorFactory.fromResource(R.mipmap.ic_pickup)));

            // Start searching for drivers
            getClosestDriver();
        });
    }

    private void getClosestDriver() {
        if (pickupLocation == null) {
            Log.e("GeoQuery", "Pickup location is null");
            resetRideRequest();
            return;
        }

        // Clear previous query if exists
        if (geoQuery != null) {
            geoQuery.removeAllListeners();
        }

        // Create new GeoFire instance for available drivers
        GeoFire availableDriversGeoFire = new GeoFire(driversAvailableRef);

        // Create new GeoQuery
        geoQuery = availableDriversGeoFire.queryAtLocation(
                new GeoLocation(pickupLocation.latitude, pickupLocation.longitude),
                radius);

        geoQuery.addGeoQueryEventListener(new GeoQueryEventListener() {
            @Override
            public void onKeyEntered(String key, GeoLocation location) {
                if (!driverFound && currentRideState == RideState.REQUESTING) {
                    driverFound = true;
                    driverFoundID = key;
                    Log.d("GeoQuery", "Driver found: " + driverFoundID);

                    // Assign this customer to the driver
                    DatabaseReference driverRef = FirebaseDatabase.getInstance()
                            .getReference("Users").child("Drivers").child(driverFoundID).child("customerRequest");

                    HashMap<String, Object> map = new HashMap<>();
                    map.put("customerRideID", FirebaseAuth.getInstance().getCurrentUser().getUid());
                    map.put("destination", destination);

                    driverRef.updateChildren(map).addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            currentRideState = RideState.DRIVER_ASSIGNED;
                            mRequest.setText("Tracking Driver...");
                            getDriverLocation();
                        } else {
                            Log.e("DriverAssign", "Failed to assign driver");
                            Toast.makeText(CustomerMapActivity.this,
                                    "Failed to assign driver",
                                    Toast.LENGTH_SHORT).show();
                            resetRideRequest();
                        }
                    });
                }
            }

            @Override
            public void onKeyExited(String key) {
                // Handle driver becoming unavailable
                if (driverFound && key.equals(driverFoundID)) {
                    Toast.makeText(CustomerMapActivity.this,
                            "Driver became unavailable",
                            Toast.LENGTH_SHORT).show();
                    resetRideRequest();
                }
            }

            @Override
            public void onKeyMoved(String key, GeoLocation location) {
                // Driver moved - could update position if needed
            }

            @Override
            public void onGeoQueryReady() {
                if (!driverFound) {
                    if (radius <= 10) {
                        radius++;
                        getClosestDriver();
                    } else {
                        Log.d("GeoQuery", "No drivers found within range.");
                        Toast.makeText(CustomerMapActivity.this,
                                "No drivers available",
                                Toast.LENGTH_SHORT).show();
                        resetRideRequest();
                    }
                }
            }

            @Override
            public void onGeoQueryError(DatabaseError error) {
                Log.e("GeoQuery", "Error: " + error.getMessage());
                Toast.makeText(CustomerMapActivity.this,
                        "Error searching for drivers",
                        Toast.LENGTH_SHORT).show();
                resetRideRequest();
            }
        });
    }

    private void getDriverLocation() {
        if (driverFoundID == null || driverFoundID.isEmpty()) {
            Toast.makeText(this, "Driver ID not available", Toast.LENGTH_SHORT).show();
            return;
        }

        driverLocationRef = driversWorkingRef.child(driverFoundID).child("l");

        driverLocationRefListener = driverLocationRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(CustomerMapActivity.this,
                            "Driver location not available!",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                List<Object> map = (List<Object>) snapshot.getValue();
                if (map == null || map.size() < 2) {
                    Toast.makeText(CustomerMapActivity.this,
                            "Invalid driver location data!",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                double locationLat = 0, locationLng = 0;
                try {
                    locationLat = Double.parseDouble(map.get(0).toString());
                    locationLng = Double.parseDouble(map.get(1).toString());
                } catch (Exception e) {
                    Log.e("LocationParse", "Error parsing location", e);
                    return;
                }

                LatLng driverLatLng = new LatLng(locationLat, locationLng);
                if (mDriverMarker != null) {
                    mDriverMarker.remove();
                }

                mDriverMarker = mMap.addMarker(new MarkerOptions()
                        .position(driverLatLng)
                        .title("Your Driver")
                        .icon(BitmapDescriptorFactory.fromResource(R.mipmap.ic_car)));

                if (pickupLocation != null) {
                    Location loc1 = new Location("");
                    loc1.setLatitude(pickupLocation.latitude);
                    loc1.setLongitude(pickupLocation.longitude);

                    Location loc2 = new Location("");
                    loc2.setLatitude(driverLatLng.latitude);
                    loc2.setLongitude(driverLatLng.longitude);

                    float distance = loc1.distanceTo(loc2);

                    if (distance < 100) {
                        mRequest.setText("Driver Arrived");
                    } else {
                        mRequest.setText(String.format("Driver: %.1f meters away", distance));
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("DriverLocation", "Error: " + error.getMessage());
            }
        });
    }

    private void cancelRideRequest() {
        if (driverFoundID != null && !driverFoundID.isEmpty()) {
            DatabaseReference driverRef = FirebaseDatabase.getInstance()
                    .getReference("Users").child("Drivers").child(driverFoundID).child("customerRequest");

            driverRef.removeValue();
        }

        if (geoQuery != null) {
            geoQuery.removeAllListeners();
        }

        if (mDriverMarker != null) {
            mDriverMarker.remove();
        }

        if (pickupMarker != null) {
            pickupMarker.remove();
        }

        radius = 1;
        driverFound = false;
        driverFoundID = "";
        currentRideState = RideState.NONE;

        mRequest.setText("Request Ride");
    }

    private void resetRideRequest() {
        currentRideState = RideState.NONE;
        mRequest.setText("Request Ride");
    }

    private void requestLocationPermissions() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;

        // Enable the My Location layer to show user's current location on the map
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        mMap.setMyLocationEnabled(true);

        // Initialize the FusedLocationProviderClient to request location updates
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        // Create LocationRequest to set parameters like accuracy and interval
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setPriority(Priority.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(10000); // Update location every 10 seconds
        locationRequest.setFastestInterval(5000); // Fastest update interval

        // Create a LocationCallback to handle location updates
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult != null && locationResult.getLocations().size() > 0) {
                    Location location = locationResult.getLastLocation();
                    if (location != null) {
                        lastLocation = location; // Store the last location
                        LatLng userLocation = new LatLng(location.getLatitude(), location.getLongitude());

                        // Move the camera to the user's current location
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 15));

                        // You can also set a marker for the user's location
                        if (pickupMarker == null) {
                            pickupMarker = mMap.addMarker(new MarkerOptions().position(userLocation).title("Your Location"));
                        }
                    }
                }
            }
        };

        // Request location updates
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

}
