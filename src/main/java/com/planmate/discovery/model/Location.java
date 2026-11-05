package com.planmate.discovery.model;

/** Where an event takes place, in the backend's EventDoc.EventLocation shape. Any part may be missing. */
public record Location(String name, String street, String zipcode, String city, String country) {
}
