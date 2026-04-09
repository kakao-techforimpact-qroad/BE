package com.qroad.be.uuid;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserAccessService {

    private final UserAccessLogRepository repository;

    public void save(String uuid, String path, boolean isNewUser) {

        UserAccessLog log = new UserAccessLog();
        log.setUuid(uuid);
        log.setPath(path);
        log.setAccessTime(LocalDateTime.now());
        log.setIsNewUser(isNewUser);

        repository.save(log);
    }
}
