package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.RestaurantQueryService.Candidate;
import java.util.*;

/** 사진 URL은 조회 계층에서 VALID인 것만 제공한다. DB 없는 식사 추천 계산. */
final class RestaurantScorer {
    static final double PHOTO_BONUS = .5, VARIETY_BONUS = .3, ROUTE_LIMIT = 800, WALK_LIMIT = 1000;
    static final String CAFE = "A05020900";
    static final Map<String,String> CATEGORIES = Map.of("A05020100","한식","A05020200","서양식",
            "A05020300","일식","A05020400","중식","A05020700","이색음식점",CAFE,"카페·전통찻집");
    record Point(double lat, double lng, String name, String address) {}
    record OtherMeal(String label, String category) {}
    record Context(Point origin, Point next, boolean hasPrevious, List<OtherMeal> others) {}
    record Ranked(Candidate candidate, double score, List<String> reasons) {}
    static double meters(Point a, Point b) {
        double lat=Math.toRadians(b.lat-a.lat), lng=Math.toRadians(b.lng-a.lng);
        double h=Math.pow(Math.sin(lat/2),2)+Math.cos(Math.toRadians(a.lat))*Math.cos(Math.toRadians(b.lat))*Math.pow(Math.sin(lng/2),2);
        return 6371000*2*Math.asin(Math.sqrt(Math.min(1,h)));
    }
    static Point point(Candidate c) { return new Point(c.lat(),c.lng(),c.name(),c.address()); }
    static double detour(Candidate c, Context x) {
        double d=meters(x.origin,point(c));
        return x.next==null?d:Math.max(0,d+meters(point(c),x.next)-meters(x.origin,x.next));
    }
    private static Optional<OtherMeal> different(Candidate c, Context x) {
        return x.others.stream().filter(o->o.category()!=null&&c.category()!=null
                &&CATEGORIES.containsKey(o.category())&&CATEGORIES.containsKey(c.category())
                &&!o.category().equals(c.category())).findFirst();
    }
    static double score(Candidate c, Context x) {
        return -detour(c,x)/1000+(c.imageUrl()!=null&&!c.imageUrl().isBlank()?PHOTO_BONUS:0)
                +(different(c,x).isPresent()?VARIETY_BONUS:0);
    }
    static List<Ranked> rank(List<Candidate> all, Context x) {
        var meals=all.stream().filter(c->!CAFE.equals(c.category())).toList();
        return (meals.isEmpty()?all:meals).stream().map(c->new Ranked(c,score(c,x),reasons(c,x)))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparingInt(r->r.candidate.distanceMeters()).thenComparing(r->r.candidate.externalId())).toList();
    }
    static List<String> reasons(Candidate c, Context x) {
        var out=new ArrayList<String>(); double detour=detour(c,x), d=meters(x.origin,point(c));
        if(x.next!=null&&detour<=ROUTE_LIMIT)out.add("다음 장소 가는 길이에요 · "+Math.round(detour/50)*50+"m만 돌아가요");
        if(x.hasPrevious&&d<=WALK_LIMIT)out.add(x.origin.name+"에서 걸어서 "+(int)Math.ceil(d*1.3/67)+"분");
        different(c,x).ifPresent(o->out.add(o.label+"("+CATEGORIES.get(o.category)+")과 다른 "+CATEGORIES.get(c.category())+"예요"));
        String town=town(c.address());
        if(x.hasPrevious&&town!=null&&town.equals(town(x.origin.address)))out.add(x.origin.name+" 근처 같은 동네("+town+")예요");
        out.add("한국관광공사에 등록된 음식점이에요"); return out.stream().limit(2).toList();
    }
    private static String town(String a) {
        return a==null?null:Arrays.stream(a.split("\\s+")).filter(t->t.matches("[가-힣0-9·]+[읍면동]")).findFirst().orElse(null);
    }
}
