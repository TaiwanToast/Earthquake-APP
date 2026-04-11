package fcu.earthquake.model;

public class EarthquakeData {
    private String type;
    private int version;
    private String id;
    private String originTime;
    private String location;
    private double latitude;
    private double longitude;
    private double depthKm;
    private double magnitude;
    private int intensity;
    private double pgaGal;

    // Getters and Setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOriginTime() { return originTime; }
    public void setOriginTime(String originTime) { this.originTime = originTime; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getDepthKm() { return depthKm; }
    public void setDepthKm(double depthKm) { this.depthKm = depthKm; }

    public double getMagnitude() { return magnitude; }
    public void setMagnitude(double magnitude) { this.magnitude = magnitude; }

    public int getIntensity() { return intensity; }
    public void setIntensity(int intensity) { this.intensity = intensity; }

    public double getPgaGal() { return pgaGal; }
    public void setPgaGal(double pgaGal) { this.pgaGal = pgaGal; }
}
