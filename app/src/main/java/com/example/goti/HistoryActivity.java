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
                .getReference("RideHistory");  // Directly reference RideHistory

        historyRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                resultsHistory.clear();  // Clear old data
                if (snapshot.exists()) {
                    for (DataSnapshot rideSnapshot : snapshot.getChildren()) {
                        // Extract ride data directly
                        String rideId = rideSnapshot.getKey();
                        Long timestamp = rideSnapshot.child("timestamp").getValue(Long.class);
                        String time = formatTimestamp(timestamp);
                        String destination = rideSnapshot.child("destination").getValue(String.class);
                        Double fare = rideSnapshot.child("fare").getValue(Double.class);
                        String driverName = rideSnapshot.child("driverName").getValue(String.class);
                        String carType = rideSnapshot.child("carType").getValue(String.class);

                        // Add to list (format fare as "$X.XX")
                        HistoryObject historyItem = new HistoryObject(
                                rideId,
                                time,
                                destination,
                                "$" + String.format(Locale.getDefault(), "%.2f", fare),
                                driverName,
                                carType
                        );
                        resultsHistory.add(historyItem);
                    }
                    mHistoryAdapter.notifyDataSetChanged();  // Refresh RecyclerView
                } else {
                    Log.d("HISTORY_DEBUG", "No rides found in RideHistory");
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
                    String rideId = snapshot.getKey();
                    String time = formatTimestamp(snapshot.child("timestamp").getValue(Long.class));
                    String destination = snapshot.child("destination").getValue(String.class);
                    String fare = "$" + snapshot.child("fare").getValue(Double.class);
                    String driverName = snapshot.child("driverName").getValue(String.class);
                    String carType = snapshot.child("carType").getValue(String.class);

                    Log.d("HistoryActivity", "Ride details - " +
                            "ID: " + rideId + ", " +
                            "Time: " + time + ", " +
                            "Destination: " + destination + ", " +
                            "Fare: " + fare);

                    HistoryObject obj = new HistoryObject(rideId, time, destination, fare, driverName, carType);
                    resultsHistory.add(obj);
                    mHistoryAdapter.notifyDataSetChanged();
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