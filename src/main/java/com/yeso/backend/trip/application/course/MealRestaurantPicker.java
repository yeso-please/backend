package com.yeso.backend.trip.application.course;
import com.yeso.backend.attraction.application.region.RestaurantQueryService.Candidate;
import java.util.*;
import java.util.random.RandomGenerator;
/** 중복 제외 후 점수 상위 5개에서 exp(score) 가중 추첨한다. */
class MealRestaurantPicker {
    static final int TOP_N=5;
    Optional<Candidate> pick(List<Candidate> candidates, Set<String> used, RandomGenerator random, RestaurantScorer.Context context) {
        var top=RestaurantScorer.rank(candidates.stream().filter(c->!used.contains(c.externalId())).toList(),context).stream().limit(TOP_N).toList();
        if(top.isEmpty())return Optional.empty();
        double max=top.getFirst().score();
        double total=top.stream().mapToDouble(r->Math.exp(r.score()-max)).sum(),draw=random.nextDouble()*total;
        for(var r:top){draw-=Math.exp(r.score()-max);if(draw<0)return Optional.of(r.candidate());}
        return Optional.of(top.getLast().candidate());
    }
}
