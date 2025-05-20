package com.capstone.xor.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(name = "version_metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VersionMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private FileMeta fileMeta;

    @Column(nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    private VersionType versionType; // SNAPSHOT or DIFF

    @Column(length = 2048)
    private String s3Key;

    @Builder.Default
    private LocalDateTime createdDate = LocalDateTime.now();

}
