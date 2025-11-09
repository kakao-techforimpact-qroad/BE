package com.qroad.be.domain.example;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

// 실제 로직을 짜는 클래스입니다.
@Service
@RequiredArgsConstructor
public class ExampleService {

    private final ExampleRepository exampleRepository;

    public List<ExampleDTO> getAllExamples() {
        return exampleRepository.findAll().stream()
                .map(ExampleDTO::fromEntity)
                .collect(Collectors.toList());
    }

    public ExampleDTO createExample(ExampleDTO dto) {
        ExampleEntity entity = dto.toEntity();
        ExampleEntity saved = exampleRepository.save(entity);
        return ExampleDTO.fromEntity(saved);
    }
}
