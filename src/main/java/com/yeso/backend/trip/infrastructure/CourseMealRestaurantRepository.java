package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.CourseMealRestaurant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CourseMealRestaurantRepository extends JpaRepository<CourseMealRestaurant, Long> {

    List<CourseMealRestaurant> findByCourseItemIdIn(Collection<Long> courseItemIds);
}
