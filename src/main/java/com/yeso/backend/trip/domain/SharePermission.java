package com.yeso.backend.trip.domain;

/** 초대 링크와 공유 링크가 같은 권한 값 집합을 쓴다. VIEW는 조회만, EDIT는 장소·순서·식당 변경까지다. */
public enum SharePermission {

    VIEW,
    EDIT;

    public static SharePermission parse(String value) {
        if (value == null) {
            throw new InvalidSharePermissionException();
        }
        try {
            return SharePermission.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidSharePermissionException();
        }
    }

    public static SharePermission parseOrDefault(String value, SharePermission fallback) {
        return value == null ? fallback : parse(value);
    }

    public boolean allowsEdit() {
        return this == EDIT;
    }
}
