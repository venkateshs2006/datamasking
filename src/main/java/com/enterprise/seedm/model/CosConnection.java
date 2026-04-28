package com.enterprise.seedm.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Entity
@Table(name = "cos_connections")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CosConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cos_name", nullable = false)
    private String cosName;

    @Column(name = "location")
    private String location;

    @Column(name = "apikey")
    private String apiKey;

    @Column(name = "service_instance_id")
    private String serviceInstanceId;

    @Column(name = "accesskey")
    private String accessKey;

    @Column(name = "secretkey")
    private String secretKey;

    @Column(name = "bucketurl")
    private String bucketUrl;

    @Column(name = "bucket_id")
    private String bucketId;

    @Column(name = "bucket_name", nullable = false)
    private String bucketName;

    @Column(name = "authendication_type")
    private String authenticationType; // e.g. HMAC or IAM

    @Column(name = "department_id", nullable = false)
    private String department;

    @Column(name = "env_type", nullable = false)
    private String envType; // source or destination

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private Long createdAt;

}
