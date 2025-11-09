package com.qroad.be.domain.example;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 요청/응답을 처리하는 클래스입니다.
@RestController
@RequestMapping("/api/examples")
@RequiredArgsConstructor
public class ExampleController {

    private final ExampleService exampleService;

    // 전체 조회
    @GetMapping
    public ResponseEntity<List<ExampleDTO>> getAllExamples() {
        return ResponseEntity.ok(exampleService.getAllExamples());
    }

    // 생성
    @PostMapping
    public ResponseEntity<ExampleDTO> createExample(@RequestBody ExampleDTO dto) {
        return ResponseEntity.ok(exampleService.createExample(dto));
    }
}
