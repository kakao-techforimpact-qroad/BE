package com.qroad.be.controller;

import com.qroad.be.dto.UserMainDTO;
import com.qroad.be.service.UserMainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/qr")
public class UserController {

    private final UserMainService userMainService;

    @GetMapping("{paperId}")
    public ResponseEntity<UserMainDTO> getPagerByPaperId(@RequestParam("paper_id") Long paperId){
        log.info("in UserController: getPagerByPaperId");

        UserMainDTO userMainDTO = userMainService.getPagerById(paperId);
        return ResponseEntity.ok(userMainDTO);
    }

}
