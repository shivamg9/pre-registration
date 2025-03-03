package io.mosip.preregistration.application.dto;

import lombok.Data;

@Data
public class RegistrationCenterResponseDto {
    private String id;
    private String name;
    private String address;
    private String langCode;
    private String centerStartTime;
    private String centerEndTime;
    private String timeZone;
    private String contactPhone;
    private String workingHours;
    private String numberOfKiosks;
    private String perKioskProcessTime;
    private String centerTypeCode;
    private String holidayLocationCode;
    private String isActive;
} 