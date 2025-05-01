package com.example.goti.historyRecyclerView;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.goti.R;

import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolders> {
    private List<HistoryObject> itemList;
    private Context context;

    public HistoryAdapter(List<HistoryObject> itemList, Context context) {
        this.itemList = itemList;
        this.context = context;
    }

    @NonNull
    @Override
    public HistoryViewHolders onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View layoutView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false);
        return new HistoryViewHolders(layoutView);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolders holder, int position) {
        HistoryObject item = itemList.get(position);
        holder.rideId.setText(item.getRideId());
        holder.time.setText(item.getTime());
        holder.destination.setText(item.getDestination());
        holder.fare.setText(item.getFare());
        holder.driverName.setText(item.getDriverName());
        holder.carType.setText(item.getCarType());
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    public class HistoryViewHolders extends RecyclerView.ViewHolder {
        public TextView rideId, time, destination, fare, driverName, carType;

        public HistoryViewHolders(View itemView) {
            super(itemView);
            rideId = itemView.findViewById(R.id.rideId);
            time = itemView.findViewById(R.id.time);
            destination = itemView.findViewById(R.id.destination);
            fare = itemView.findViewById(R.id.fare);
            driverName = itemView.findViewById(R.id.driverName);
            carType = itemView.findViewById(R.id.carType);
        }
    }
}