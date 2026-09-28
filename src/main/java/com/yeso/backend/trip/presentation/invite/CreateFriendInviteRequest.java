package com.yeso.backend.trip.presentation.invite;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateFriendInviteRequest(@NotNull @Positive Long friendUserId) {
}
