package com.qroad.be.domain.example;

import lombok.*;

// 쓰임에 맞게 데이터를 가져오는 클래스입니다.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExampleDTO {
    private Long id;
    private String title;
    private String content;

    // Entity → DTO 변환
    public static ExampleDTO fromEntity(ExampleEntity entity) {
        return ExampleDTO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .content(entity.getContent())
                .build();
    }

    // DTO → Entity 변환
    public ExampleEntity toEntity() {
        return ExampleEntity.builder()
                .id(this.id)
                .title(this.title)
                .content(this.content)
                .build();
    }

}
