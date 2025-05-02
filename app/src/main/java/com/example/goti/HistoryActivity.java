// HistoryActivity.java
package com.example.goti;

import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.goti.historyRecyclerView.HistoryAdapter;
import com.example.goti.historyRecyclerView.HistoryObject;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends AppCompatActivity {
    private String customerOrDriver, userId;
    private RecyclerView mHistoryRecyclerView;
    private HistoryAdapter mHistoryAdapter;
    private List<HistoryObject> resultsHistory = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_history);

        mHistoryRecyclerView = findViewById(R.id.historyRecyclerView);
        mHistoryRecyclerView.setNestedScrollingEnabled(false);
        mHistoryRecyclerView.setHasFixedSize(true);

        LinearLayoutManager mHistoryLayoutManager = new LinearLayoutManager(this);
        mHistoryRecyclerView.setLayoutManager(mHistoryLayoutManager);
        mHistoryAdapter = new HistoryAdapter(resultsHistory, this);
        mHistoryRecyclerView.setAdapter(mHistoryAdapter);

        customerOrDriver = getIntent().getExtras().getString("customerOrDriver");
        userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        getUserHistoryIds();
    }

    private void getUserHistoryIds() {
        DatabaseReference historyRef = FirebaseDatabase.getInstance()
                .getReference("RideHistory");

        // Query only rides where customerId matches current user ID
        Query userHistoryQuery = historyRef.orderByChild("customerId").equalTo(userId);

        userHistoryQuery.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                resultsHistory.clear();
                if (snapshot.exists()) {
                    for (DataSnapshot rideSnapshot : snapshot.getChildren()) {
                        String rideId = rideSnapshot.getKey();
                        fetchRideInformation(rideId); // Call for each ride ID
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("HISTORY_DEBUG", "Failed to read RideHistory: " + error.getMessage());
            }
        });
    }

    private void fetchRideInformation(String rideKey) {
        DatabaseReference historyRef = FirebaseDatabase.getInstance().getReference()
                .child("RideHistory")
                .child(rideKey);

        historyRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    // Verify customerId matches current user (additional safety check)
                    String customerId = snapshot.child("customerId").getValue(String.class);
                    if (customerId != null && customerId.equals(userId)) {
                        String rideId = snapshot.getKey();
                        Long timestamp = snapshot.child("timestamp").getValue(Long.class);
                        String time = formatTimestamp(timestamp);

                        // Get destination (already stored with proper name from DriverMapActivity)
                        String destination = snapshot.child("destination").getValue(String.class);
                        if (destination == null) {
                            destination = "Unknown destination";
                        }

                        Double fareValue = snapshot.child("fare").getValue(Double.class);
                        String fare = fareValue != null ? "$" + String.format(Locale.getDefault(), "%.2f", fareValue) : "$0.00";
                        String driverName = snapshot.child("driverName").getValue(String.class);
                        String carType = snapshot.child("carType").getValue(String.class);

                        HistoryObject obj = new HistoryObject(rideId, time, destination, fare, driverName, carType);
                        resultsHistory.add(obj);
                        mHistoryAdapter.notifyDataSetChanged();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("HistoryActivity", "Error fetching ride details", error.toException());
            }
        });
    }

    private String formatTimestamp(Long timestamp) {
        if (timestamp == null) return "Unknown time";
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
}