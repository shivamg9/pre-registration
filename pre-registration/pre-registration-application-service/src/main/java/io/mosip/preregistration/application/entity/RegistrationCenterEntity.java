package io.mosip.preregistration.application.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "registration_center", schema = "prereg") // IMPORTANT: Specify schema
public class RegistrationCenterEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name")
    private String name;

    @Column(name = "addr_line1")
    private String addressLine1;

    @Column(name = "addr_line2")
    private String addressLine2;

    @Column(name = "addr_line3")
    private String addressLine3;

    @Column(name = "lang_code")
    private String langCode;

    // Add other fields as necessary, but keep it minimal for performance.
    // You don't *need* all fields if you're only using a few.
}