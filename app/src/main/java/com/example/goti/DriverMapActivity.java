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
import com.google.android.gms.maps.model.LatLng;
import com.example.goti.databinding.ActivityDriverMapBinding;
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

    private GoogleMap mMap;
    private ActivityDriverMapBinding binding;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private boolean firstLocationUpdate = true;

    private DatabaseReference driverLocationRef;
    private DatabaseReference assignedCustomerRef;
    private GeoFire geoFire;
    private String userID;
    private Button mLogout;
    private String customerID = "";
    private Marker pickupMarker;
    private DatabaseReference assignedCustomerPickupLocationRef;
    private ValueEventListener assignedCustomerPickupLocationRefListener;
    private LinearLayout mCustomerInfo;
    private ImageView mCustomerProfileImage;
    private TextView mCustomerName, mCustomerPhone, mCustomerDestination;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDriverMapBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        userID = FirebaseAuth.getInstance().getCurrentUser().getUid();
        driverLocationRef = FirebaseDatabase.getInstance().getReference("driversAvailable");
        geoFire = new GeoFire(driverLocationRef);

        requestLocationPermissions();

        mCustomerInfo = findViewById(R.id.customerInfo);

        mCustomerProfileImage = findViewById(R.id.customerProfileImage);

        mCustomerName = findViewById(R.id.customerName);
        mCustomerPhone = findViewById(R.id.customerPhone);
        mCustomerDestination = findViewById(R.id.customerDestination);

        mLogout = findViewById(R.id.logout);
        mLogout.setOnClickListener(v -> {
            cleanupDriverLocation();
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(DriverMapActivity.this, MainActivity.class));
            finish();
        });

        getAssignedCustomer();
    }

    private void cleanupDriverLocation() {
        if (userID != null) {
            DatabaseReference refAvailable = FirebaseDatabase.getInstance().getReference("driversAvailable");
            DatabaseReference refWorking = FirebaseDatabase.getInstance().getReference("driversWorking");
            GeoFire geoFireAvailable = new GeoFire(refAvailable);
            GeoFire geoFireWorking = new GeoFire(refWorking);

            geoFireAvailable.removeLocation(userID);
            geoFireWorking.removeLocation(userID);
        }
    }

    private void getAssignedCustomer() {
        String driverId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        assignedCustomerRef = FirebaseDatabase.getInstance()
                .getReference().child("Users").child("Drivers").child(driverId).
                child("customerRequest").child("customerRideID");

        assignedCustomerRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    customerID = dataSnapshot.getValue(String.class);
                    if (customerID != null && !customerID.isEmpty()) {
                        getAssignedCustomerPickupLocation();
                        getAssignedCustomerInfo();
                        getAssignedCustomerDestination();
                    } else {
                        customerID = "";
                        removePickupMarkerAndListener();
                    }
                } else {
                    customerID = "";
                    removePickupMarkerAndListener();
                    mCustomerInfo.setVisibility(View.GONE);
                    mCustomerName.setText("");
                    mCustomerPhone.setText("");
                    mCustomerDestination.setText("Destination: --");
                    mCustomerProfileImage.setImageResource(R.drawable.account_circle_24);

                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e("DriverAssign", "Error checking assigned customer", databaseError.toException());
            }
        });
    }

    private void getAssignedCustomerDestination(){
        String driverId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        assignedCustomerRef = FirebaseDatabase.getInstance()
                .getReference().child("Users").child("Drivers").child(driverId).
                child("customerRequest").child("destination");

        assignedCustomerRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    String destination = dataSnapshot.getValue().toString();
                    mCustomerDestination.setText("Destination: " + destination);
                } else {
                    mCustomerDestination.setText("Destination: --");
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e("DriverAssign", "Error checking assigned customer", databaseError.toException());
            }
        });
    }

    private void removePickupMarkerAndListener() {
        if (pickupMarker != null) {
            pickupMarker.remove();
            pickupMarker = null;
        }
        if (assignedCustomerPickupLocationRefListener != null && assignedCustomerPickupLocationRef != null) {
            assignedCustomerPickupLocationRef.removeEventListener(assignedCustomerPickupLocationRefListener);
        }
    }

    private void getAssignedCustomerPickupLocation() {
        if (customerID == null || customerID.isEmpty()) return;

        assignedCustomerPickupLocationRef = FirebaseDatabase.getInstance().getReference()
                .child("CustomerRequest").child(customerID).child("l");

        assignedCustomerPickupLocationRefListener = assignedCustomerPickupLocationRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists() || snapshot.getValue() == null) {
                    removePickupMarkerAndListener();
                    return;
                }

                try {
                    List<Object> map = (List<Object>) snapshot.getValue();
                    if (map == null || map.size() < 2) return;

                    double locationLat = parseDouble(map.get(0));
                    double locationLng = parseDouble(map.get(1));

                    LatLng pickupLatLng = new LatLng(locationLat, locationLng);

                    if (pickupMarker != null) {
                        pickupMarker.remove();
                    }

                    pickupMarker = mMap.addMarker(new MarkerOptions()
                            .position(pickupLatLng)
                            .title("Pickup Location"));

                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pickupLatLng, 18));
                } catch (Exception e) {
                    Log.e("PickupLocation", "Error parsing pickup location", e);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Error retrieving customer pickup location: " + error.getMessage());
            }
        });
    }

    private double parseDouble(Object value) {
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private void getAssignedCustomerInfo() {
        mCustomerInfo.setVisibility(View.VISIBLE);
        DatabaseReference mCustomerDatabase = FirebaseDatabase.getInstance().getReference()
                .child("Users")
                .child("Customers")
                .child(customerID);
        mCustomerDatabase.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists() && snapshot.getChildrenCount()>0) {
                    // Get name if exists
                    if (snapshot.hasChild("name")) {
                        mCustomerName.setText(snapshot.child("name").getValue(String.class));
                    }
                    // Get phone if exists
                    if (snapshot.hasChild("phone")) {
                        mCustomerPhone.setText(snapshot.child("phone").getValue(String.class));
                    }
                    // Get profile image if exists
                    if (snapshot.hasChild("profileImageUrl")) {
                        Glide.with(DriverMapActivity.this)
                                .load(snapshot.child("profileImageUrl").getValue(String.class))
                                .into(mCustomerProfileImage);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(DriverMapActivity.this,
                        "Failed to load user data: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            setupLocationUpdates();
        } else {
            requestLocationPermissions();
        }
    }

    private void setupLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(1000)
                .setMaxUpdateDelayMillis(100)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location == null) return;

                updateDriverLocation(location);

                LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                if (firstLocationUpdate) {
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 18));
                    firstLocationUpdate = false;
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());
        }
    }

    private void updateDriverLocation(Location location) {
        if (location == null || userID == null) return;

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
        }
    }

    private void requestLocationPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupDriverLocation();
        if (fusedLocationProviderClient != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }
    }
}