package com.yeso.backend.trip.presentation.invite;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Authorization: Bearer {guestSessionToken}에서 참여자 ID를 주입받는다(회원 JWT와는 다른 자격 증명). */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentGuestParticipant {
}
