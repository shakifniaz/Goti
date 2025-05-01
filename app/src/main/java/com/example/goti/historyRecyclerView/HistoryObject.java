// HistoryObject.java
package com.example.goti.historyRecyclerView;

public class HistoryObject {
    private String rideId;
    private String time;
    private String destination;
    private String fare;
    private String driverName;
    private String carType;

    public HistoryObject(String rideId, String time, String destination, String fare, String driverName, String carType) {
        this.rideId = rideId;
        this.time = time;
        this.destination = destination;
        this.fare = fare;
        this.driverName = driverName;
        this.carType = carType;
    }

    public String getRideId() { return rideId; }
    public String getTime() { return time; }
    public String getDestination() { return destination; }
    public String getFare() { return fare; }
    public String getDriverName() { return driverName; }
    public String getCarType() { return carType; }
}