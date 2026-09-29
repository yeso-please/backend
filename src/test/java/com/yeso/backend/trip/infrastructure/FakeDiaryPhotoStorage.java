package com.yeso.backend.trip.infrastructure;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only object store fake; uploaded bytes stay in memory and URLs are not publicly reachable. */
@Component
public class FakeDiaryPhotoStorage implements DiaryPhotoStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, bytes.clone());
    }

    @Override
    public String presignedGet(String key, Duration lifetime) {
        if (!objects.containsKey(key)) throw new IllegalStateException("missing object");
        return "https://private.test/" + key + "?expiresIn=" + lifetime.toSeconds();
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    public boolean contains(String key) {
        return objects.containsKey(key);
    }

    public int size() {
        return objects.size();
    }

    public void reset() {
        objects.clear();
    }
}
