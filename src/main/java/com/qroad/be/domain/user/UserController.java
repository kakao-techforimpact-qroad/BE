package com.qroad.be.domain.user;

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

    @GetMapping("{qr_key}")
    public ResponseEntity<UserMainDTO> getPagerByQrKey(@RequestParam("qr_key") String qrKey){
        log.info("in UserController: getPagerByQrKey");

        UserMainDTO userMainDTO = userMainService.getPagerByQrKey(qrKey);
        return ResponseEntity.ok(userMainDTO);
    }

}
