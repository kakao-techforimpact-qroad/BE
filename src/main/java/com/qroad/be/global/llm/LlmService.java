package com.qroad.be.global.llm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LlmService {
    
    public String generateResponse(String prompt) {
        return "Hello, world!";
    }
}