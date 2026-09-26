package com.railops.producer;

import java.util.List;

/** The fixed set of simulated rail services, each owned by one source system. */
public final class ServiceCatalog {

    public record RailService(String name, String source, List<String> messages) {
    }

    public static final List<RailService> SERVICES = List.of(
            new RailService("route-service", "ATS", List.of(
                    "Route R-7 setting delayed at Central junction",
                    "Automatic route request rejected for platform 3",
                    "Route conflict detected between T-118 and T-204")),
            new RailService("train-tracking", "ATS", List.of(
                    "Train T-212 position update delayed",
                    "Train T-305 lost track circuit occupancy",
                    "Train describer mismatch on line 2 eastbound")),
            new RailService("signal-service", "CBTC", List.of(
                    "Signal SG-14 failed to clear",
                    "Zone controller ZC-2 communication timeout",
                    "Movement authority reduced for train T-127",
                    "Wayside radio link degraded in sector 4")),
            new RailService("power-supply", "SCADA", List.of(
                    "Traction substation TSS-3 breaker tripped",
                    "Third rail voltage below threshold in section 12",
                    "Auxiliary power switched to backup at depot")),
            new RailService("timetable-service", "TMS", List.of(
                    "Timetable deviation above 3 minutes on line 1",
                    "Crew roster conflict for evening shift",
                    "Service pattern update not propagated to depot")),
            new RailService("passenger-info", "PIS", List.of(
                    "Platform display PD-22 not responding",
                    "Arrival announcements out of sync at Riverside",
                    "Passenger information feed delayed by 90 seconds")));

    private ServiceCatalog() {
    }
}
