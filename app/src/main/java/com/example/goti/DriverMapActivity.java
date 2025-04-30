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

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private DatabaseReference driverLocationRef;
    private DatabaseReference assignedCustomerRef;
    private GeoFire geoFire;
    private String userID;
    private Button mLogout;
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

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        userID = FirebaseAuth.getInstance().getCurrentUser().getUid();
        driverLocationRef = FirebaseDatabase.getInstance().getReference("driversAvailable");
        geoFire = new GeoFire(driverLocationRef);

        requestLocationPermissions();
        initializeViews();
        setupLogoutButton();
        setupCustomerAssignmentListener();
    }

    private void initializeViews() {
        mCustomerInfo = findViewById(R.id.customerInfo);
        mCustomerProfileImage = findViewById(R.id.customerProfileImage);
        mCustomerName = findViewById(R.id.customerName);
        mCustomerPhone = findViewById(R.id.customerPhone);
        mCustomerDestination = findViewById(R.id.customerDestination);
        mLogout = findViewById(R.id.logout);
    }

    private void setupLogoutButton() {
        mLogout.setOnClickListener(v -> {
            cleanupDriverLocation();
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(DriverMapActivity.this, MainActivity.class));
            finish();
        });
    }

    private void setupCustomerAssignmentListener() {
        assignedCustomerRef = FirebaseDatabase.getInstance()
                .getReference("Users/Drivers/" + userID + "/customerRequest/customerRideID");

        assignedCustomerRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    customerID = dataSnapshot.getValue(String.class);
                    if (customerID != null && !customerID.isEmpty()) {
                        showCustomerInfo();
                    } else {
                        clearCustomerInfo();
                    }
                } else {
                    clearCustomerInfo();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e("DriverAssign", "Error checking assignment", databaseError.toException());
            }
        });
    }

    private void showCustomerInfo() {
        getAssignedCustomerPickupLocation();
        getAssignedCustomerInfo();
        getAssignedCustomerDestination();
        mCustomerInfo.setVisibility(View.VISIBLE);
    }

    private void clearCustomerInfo() {
        customerID = "";
        removeAllMarkers();
        mCustomerInfo.setVisibility(View.GONE);
        resetCustomerInfoFields();
    }

    private void removeAllMarkers() {
        if (pickupMarker != null) {
            pickupMarker.remove();
            pickupMarker = null;
        }
        if (destinationMarker != null) {
            destinationMarker.remove();
            destinationMarker = null;
        }
    }

    private void resetCustomerInfoFields() {
        mCustomerName.setText("");
        mCustomerPhone.setText("");
        mCustomerDestination.setText("Destination: --");
        mCustomerProfileImage.setImageResource(R.drawable.account_circle_24);
    }

    private void getAssignedCustomerPickupLocation() {
        if (assignedCustomerPickupLocationRefListener != null && assignedCustomerPickupLocationRef != null) {
            assignedCustomerPickupLocationRef.removeEventListener(assignedCustomerPickupLocationRefListener);
        }

        assignedCustomerPickupLocationRef = FirebaseDatabase.getInstance()
                .getReference("CustomerRequest/" + customerID + "/l");

        assignedCustomerPickupLocationRefListener = assignedCustomerPickupLocationRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    return;
                }

                try {
                    List<Object> location = (List<Object>) snapshot.getValue();
                    if (location != null && location.size() >= 2) {
                        updatePickupMarker(location);
                    }
                } catch (Exception e) {
                    Log.e("PickupLocation", "Error parsing location", e);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Error getting pickup location");
            }
        });
    }

    private void updatePickupMarker(List<Object> location) {
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

        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pickupLatLng, 15));
    }

    private void getAssignedCustomerInfo() {
        DatabaseReference mCustomerDatabase = FirebaseDatabase.getInstance().getReference()
                .child("Users/Customers/" + customerID);

        mCustomerDatabase.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
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
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("CustomerInfo", "Error: " + error.getMessage());
            }
        });
    }

    private void getAssignedCustomerDestination() {
        DatabaseReference customerRequestRef = FirebaseDatabase.getInstance()
                .getReference("CustomerRequest/" + customerID);

        customerRequestRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    String destinationName = dataSnapshot.child("destinationName").getValue(String.class);
                    mCustomerDestination.setText("Destination: " + destinationName);

                    List<Object> latLng = (List<Object>) dataSnapshot.child("destinationLatLng").getValue();
                    if (latLng != null && latLng.size() >= 2) {
                        updateDestinationMarker(latLng, destinationName);
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e("Destination", "Error getting destination", databaseError.toException());
            }
        });
    }

    private void updateDestinationMarker(List<Object> latLng, String destinationName) {
        double lat = Double.parseDouble(latLng.get(0).toString());
        double lng = Double.parseDouble(latLng.get(1).toString());
        LatLng destination = new LatLng(lat, lng);

        if (destinationMarker != null) {
            destinationMarker.remove();
        }

        destinationMarker = mMap.addMarker(new MarkerOptions()
                .position(destination)
                .title("Destination: " + destinationName)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
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

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, null);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupDriverLocation();
        if (fusedLocationProviderClient != null && locationCallback != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }
        if (assignedCustomerPickupLocationRefListener != null && assignedCustomerPickupLocationRef != null) {
            assignedCustomerPickupLocationRef.removeEventListener(assignedCustomerPickupLocationRefListener);
        }
    }
}