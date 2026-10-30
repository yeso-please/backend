package com.yeso.backend.trip.application.course;
import com.yeso.backend.attraction.application.region.RestaurantQueryService.Candidate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
class MealRestaurantPickerTest {
    static Candidate c(String id,String category,double lat,double lng,String photo){return new Candidate(id,id,category,"강릉시 중앙동",null,null,lat,lng,null,photo,null,null,null,null,100);}
    static RestaurantScorer.Point p(double x,double y){return new RestaurantScorer.Point(x,y,"경포대","강릉시 중앙동");}
    static RestaurantScorer.Context ctx(RestaurantScorer.Point next){return new RestaurantScorer.Context(p(0,0),next,true,List.of());}
    @Test void routeBeatsCloserOffRoute(){var x=ctx(p(0,.04));assertThat(RestaurantScorer.score(c("route",null,0,.02,null),x)).isGreaterThan(RestaurantScorer.score(c("near",null,.005,0,null),x));}
    @Test void photoAndVariety(){var a=c("a","A05020300",0,0,null);var b=c("b","A05020300",0,0,"photo");assertThat(RestaurantScorer.score(b,ctx(null))-RestaurantScorer.score(a,ctx(null))).isEqualTo(.5);var x=new RestaurantScorer.Context(p(0,0),null,true,List.of(new RestaurantScorer.OtherMeal("점심","A05020100")));assertThat(RestaurantScorer.score(a,x)).isEqualTo(.3);}
    @Test void lastMealUsesOriginDistance(){var a=c("a",null,0,.01,null);assertThat(RestaurantScorer.detour(a,ctx(null))).isEqualTo(RestaurantScorer.meters(p(0,0),RestaurantScorer.point(a)));}
    @Test void cafeFilterAndFallback(){var cafe=c("c",RestaurantScorer.CAFE,0,0,null);var meal=c("m","A05020100",0,0,null);assertThat(RestaurantScorer.rank(List.of(cafe,meal),ctx(null))).extracting(r->r.candidate().externalId()).containsExactly("m");assertThat(RestaurantScorer.rank(List.of(cafe),ctx(null))).hasSize(1);}
    @Test void topFiveAndDeterminism(){var pool=new ArrayList<Candidate>();for(int i=0;i<10;i++)pool.add(c("r"+i,"A05020100",0,i*.001,null));var picker=new MealRestaurantPicker();var first=picker.pick(pool,Set.of(),new SplittableRandom(42),ctx(null));assertThat(first).isEqualTo(picker.pick(pool,Set.of(),new SplittableRandom(42),ctx(null)));for(int i=0;i<100;i++)assertThat(picker.pick(pool,Set.of(),new SplittableRandom(i),ctx(null)).orElseThrow().externalId()).isIn("r0","r1","r2","r3","r4");}
    @Test void excludesUsedBeforeCafeFallback(){var meal=c("m","A05020100",0,0,null);var cafe=c("c",RestaurantScorer.CAFE,0,0,null);assertThat(new MealRestaurantPicker().pick(List.of(meal,cafe),Set.of("m"),new SplittableRandom(1),ctx(null)).orElseThrow()).isEqualTo(cafe);}
    @Test void reasonsPriorityAndLimit(){var x=ctx(p(0,.02));assertThat(RestaurantScorer.reasons(c("a","A05020100",0,0,null),x)).containsExactly("다음 장소 가는 길이에요 · 0m만 돌아가요","경포대에서 걸어서 0분");}
    @Test void fallbackReasons(){var x=new RestaurantScorer.Context(p(0,0),null,false,List.of());assertThat(RestaurantScorer.reasons(c("a",null,0,.1,null),x)).containsExactly("한국관광공사에 등록된 음식점이에요");}
    @Test void reasonBoundariesAndUnknownCategory() {
        double degree = 180 / (Math.PI * 6371000);
        for (double meters : new double[]{799.99,800.01}) {
            var c=c("r",null,meters/2*degree,0,null);
            assertThat(RestaurantScorer.reasons(c,ctx(p(0,0))).stream().anyMatch(r->r.startsWith("다음 장소"))).isEqualTo(meters<800);
        }
        for (double meters : new double[]{999.99,1000.01}) {
            var c=c("r",null,meters*degree,0,null);
            assertThat(RestaurantScorer.reasons(c,ctx(null)).stream().anyMatch(r->r.contains("걸어서"))).isEqualTo(meters<1000);
        }
        var x=new RestaurantScorer.Context(p(0,0),null,true,List.of(new RestaurantScorer.OtherMeal("점심","unknown")));
        assertThat(RestaurantScorer.reasons(c("r","A05020300",0,.1,null),x)).containsExactly("경포대 근처 같은 동네(중앙동)예요","한국관광공사에 등록된 음식점이에요");
    }
}
